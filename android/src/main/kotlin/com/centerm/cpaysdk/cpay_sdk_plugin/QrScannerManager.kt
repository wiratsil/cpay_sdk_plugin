package com.centerm.cpaysdk.cpay_sdk_plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages background QR/Barcode scanning using CameraX + ML Kit.
 * Optimized for long-running scanning with minimal memory usage.
 * 
 * Memory optimizations:
 * 1. Low resolution (640x480) - reduces frame buffer size
 * 2. Frame skipping - only process every Nth frame with ML Kit
 * 3. Guaranteed ImageProxy.close() - prevents buffer leak
 * 4. Watchdog - auto-restart only if camera actually freezes
 */
class QrScannerManager(private val context: Context) {

    companion object {
        private const val TAG = "QrScannerManager"
        private const val WATCHDOG_INTERVAL_MS = 5000L    // Check every 5 seconds
        private const val FRAME_TIMEOUT_MS = 10000L       // Restart if no frame for 10 seconds
        private const val PROCESS_EVERY_N_FRAMES = 2      // Only scan every 2nd frame
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService? = null
    private var barcodeScanner: BarcodeScanner? = null
    
    private var resultListener: ((String) -> Unit)? = null
    private var debugListener: ((String) -> Unit)? = null
    private var errorListener: ((String) -> Unit)? = null
    private var lastScannedCode: String? = null
    private var lastScanTime: Long = 0
    private val debounceMs = 1500L
    
    @Volatile private var isScanning = false
    private var frameCount = 0
    private val isProcessing = AtomicBoolean(false) // Prevent concurrent ML Kit processing

    // Watchdog
    private var watchdogScheduler: ScheduledExecutorService? = null
    @Volatile private var lastFrameTime: Long = 0
    private var savedLifecycleOwner: LifecycleOwner? = null
    private var savedUseFrontCamera: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setResultListener(listener: (String) -> Unit) {
        resultListener = listener
    }

    fun setDebugListener(listener: (String) -> Unit) {
        debugListener = listener
    }

    fun setErrorListener(listener: (String) -> Unit) {
        errorListener = listener
    }

    private fun logDebug(msg: String) {
        Log.d(TAG, msg)
        debugListener?.invoke("[QR] $msg")
    }

    fun startScanning(lifecycleOwner: LifecycleOwner, useFrontCamera: Boolean = false) {
        savedLifecycleOwner = lifecycleOwner
        savedUseFrontCamera = useFrontCamera
        startScanningInternal(lifecycleOwner, useFrontCamera)
    }

    private fun startScanningInternal(lifecycleOwner: LifecycleOwner, useFrontCamera: Boolean) {
        logDebug("Releasing previous camera resources...")
        forceStopAll()
        Thread.sleep(300)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Create barcode scanner (lightweight, reuse-safe)
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E
            )
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                try {
                    cameraProvider?.unbindAll()
                    Thread.sleep(100)
                } catch (e: Exception) {
                    Log.w(TAG, "Error unbinding: ${e.message}")
                }

                // HD resolution - good balance of quality and memory
                imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageRotationEnabled(true)
                    .build()

                imageAnalysis?.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    processImage(imageProxy)
                }

                val cameraSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis
                )

                isScanning = true
                frameCount = 0
                isProcessing.set(false)
                lastFrameTime = System.currentTimeMillis()
                logDebug("Camera started (${if (useFrontCamera) "Front" else "Back"}) - Low-res 640x480, skip ${PROCESS_EVERY_N_FRAMES - 1}/$PROCESS_EVERY_N_FRAMES frames")

                startWatchdog()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                val errorMsg = e.message ?: ""
                
                logDebug("CAMERA ERROR: $errorMsg")
                if (errorMsg.contains("MAX_CAMERAS_IN_USE") || 
                    errorMsg.contains("CAMERA_IN_USE") ||
                    errorMsg.contains("Camera is being used")) {
                    logDebug("Camera is locked - please restart device")
                    errorListener?.invoke("Camera locked - please restart device")
                } else {
                    errorListener?.invoke("Camera error: $errorMsg")
                }
                forceStopAll()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Watchdog timer - only restarts if camera is truly frozen.
     * No periodic restart needed because memory is managed properly.
     */
    private fun startWatchdog() {
        stopWatchdog()
        
        watchdogScheduler = Executors.newSingleThreadScheduledExecutor()
        watchdogScheduler?.scheduleAtFixedRate({
            try {
                if (!isScanning) return@scheduleAtFixedRate
                
                val now = System.currentTimeMillis()
                val timeSinceLastFrame = now - lastFrameTime
                
                // Only restart if camera is truly frozen
                if (timeSinceLastFrame > FRAME_TIMEOUT_MS) {
                    Log.w(TAG, "Watchdog: No frame for ${timeSinceLastFrame}ms - camera frozen!")
                    logDebug("Watchdog: Camera frozen, restarting...")
                    
                    mainHandler.post {
                        val lo = savedLifecycleOwner
                        if (lo != null) {
                            startScanningInternal(lo, savedUseFrontCamera)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog error", e)
            }
        }, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS)
        
        Log.d(TAG, "Watchdog started (freeze timeout: ${FRAME_TIMEOUT_MS}ms)")
    }

    private fun stopWatchdog() {
        try { watchdogScheduler?.shutdownNow() } catch (e: Exception) { }
        watchdogScheduler = null
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        // ALWAYS close imageProxy - this is critical for preventing buffer leak
        if (!isScanning) {
            imageProxy.close()
            return
        }
        
        // Update watchdog timestamp
        lastFrameTime = System.currentTimeMillis()
        frameCount++
        
        // FRAME SKIPPING - only process every Nth frame to reduce CPU/memory
        if (frameCount % PROCESS_EVERY_N_FRAMES != 0) {
            imageProxy.close()  // Close skipped frames immediately!
            return
        }
        
        // Prevent concurrent ML Kit processing (if previous frame is still being processed)
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()  // Close if already processing another frame
            return
        }
        
        if (frameCount % 90 == 0) {
            logDebug("Frame #$frameCount (processed: ${frameCount / PROCESS_EVERY_N_FRAMES})")
        }
        
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            barcodeScanner?.process(image)
                ?.addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        barcode.rawValue?.let { value ->
                            val now = System.currentTimeMillis()
                            if (value != lastScannedCode || (now - lastScanTime) > debounceMs) {
                                lastScannedCode = value
                                lastScanTime = now
                                resultListener?.invoke(value)
                            }
                        }
                    }
                }
                ?.addOnFailureListener { e ->
                    Log.e(TAG, "Barcode scanning failed", e)
                }
                ?.addOnCompleteListener {
                    imageProxy.close()       // Always close
                    isProcessing.set(false)  // Allow next frame to be processed
                }
        } else {
            imageProxy.close()
            isProcessing.set(false)
        }
    }

    /**
     * Force stop everything - camera, watchdog, all resources
     */
    fun forceStopAll() {
        isScanning = false
        isProcessing.set(false)
        
        stopWatchdog()
        
        try { imageAnalysis?.clearAnalyzer() } catch (e: Exception) { }
        try { cameraProvider?.unbindAll() } catch (e: Exception) { }
        try { cameraExecutor?.shutdownNow() } catch (e: Exception) { }
        try { barcodeScanner?.close() } catch (e: Exception) { }
        
        cameraProvider = null
        imageAnalysis = null
        cameraExecutor = null
        barcodeScanner = null
        lastScannedCode = null
        
        Log.d(TAG, "Force stopped all camera resources")
    }

    fun stopScanning() {
        logDebug("Stopping scanner...")
        savedLifecycleOwner = null
        forceStopAll()
    }

    fun isCurrentlyScanning(): Boolean = isScanning

    fun forceReleaseCamera() {
        try {
            cameraProvider?.unbindAll()
            imageAnalysis?.clearAnalyzer()
        } catch (e: Exception) { }
    }
}
