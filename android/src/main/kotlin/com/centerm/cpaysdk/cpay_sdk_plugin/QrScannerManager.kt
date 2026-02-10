package com.centerm.cpaysdk.cpay_sdk_plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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

/**
 * Manages background QR/Barcode scanning using CameraX + ML Kit.
 * Includes watchdog timer to auto-restart camera when it freezes.
 */
class QrScannerManager(private val context: Context) {

    companion object {
        private const val TAG = "QrScannerManager"
        private const val WATCHDOG_INTERVAL_MS = 5000L   // Check every 5 seconds
        private const val FRAME_TIMEOUT_MS = 10000L      // Restart if no frame for 10 seconds
        private const val AUTO_RESTART_INTERVAL_MS = 60000L // Auto-restart every 60 seconds to clear mem/buffer
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
    
    private var isScanning = false
    private var frameCount = 0

    // Watchdog & auto-restart
    private var watchdogScheduler: ScheduledExecutorService? = null
    private var lastFrameTime: Long = 0
    private var cameraStartTime: Long = 0
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
        // Save for auto-restart
        savedLifecycleOwner = lifecycleOwner
        savedUseFrontCamera = useFrontCamera
        autoRestartCount = 0

        startScanningInternal(lifecycleOwner, useFrontCamera)
    }

    private fun startScanningInternal(lifecycleOwner: LifecycleOwner, useFrontCamera: Boolean) {
        // Force stop first
        logDebug("Releasing previous camera resources...")
        forceStopAll()
        Thread.sleep(300)

        cameraExecutor = Executors.newSingleThreadExecutor()

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

                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
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
                lastFrameTime = System.currentTimeMillis()
                cameraStartTime = System.currentTimeMillis()
                logDebug("Camera started (${if (useFrontCamera) "Front" else "Back"})")

                // Start watchdog
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
     * Watchdog timer - checks if camera is still producing frames.
     * 1. Freeze detection: restart if no frame for FRAME_TIMEOUT_MS
     * 2. Periodic restart: restart every AUTO_RESTART_INTERVAL_MS to clear mem/buffer
     */
    private fun startWatchdog() {
        stopWatchdog()
        
        watchdogScheduler = Executors.newSingleThreadScheduledExecutor()
        watchdogScheduler?.scheduleAtFixedRate({
            try {
                if (!isScanning) return@scheduleAtFixedRate
                
                val now = System.currentTimeMillis()
                val timeSinceLastFrame = now - lastFrameTime
                val timeSinceStart = now - cameraStartTime
                
                // Check 1: Camera frozen (no frames)
                if (timeSinceLastFrame > FRAME_TIMEOUT_MS) {
                    Log.w(TAG, "Watchdog: No frame for ${timeSinceLastFrame}ms - camera frozen!")
                    logDebug("Watchdog: Camera frozen, restarting...")
                    
                    mainHandler.post {
                        val lo = savedLifecycleOwner
                        if (lo != null) {
                            startScanningInternal(lo, savedUseFrontCamera)
                        }
                    }
                    return@scheduleAtFixedRate
                }
                
                // Check 2: Periodic restart to clear memory/buffer
                if (timeSinceStart > AUTO_RESTART_INTERVAL_MS) {
                    logDebug("Watchdog: Periodic restart (${timeSinceStart / 1000}s elapsed) - clearing mem/buffer...")
                    
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
        
        Log.d(TAG, "Watchdog started (freeze: ${FRAME_TIMEOUT_MS}ms, periodic: ${AUTO_RESTART_INTERVAL_MS / 1000}s)")
    }

    private fun stopWatchdog() {
        try {
            watchdogScheduler?.shutdownNow()
        } catch (e: Exception) { }
        watchdogScheduler = null
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        if (!isScanning) {
            imageProxy.close()
            return
        }
        
        // Update watchdog timestamp
        lastFrameTime = System.currentTimeMillis()
        
        frameCount++
        if (frameCount % 30 == 1) {
            logDebug("Processing frame #$frameCount")
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
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    /**
     * Force stop everything - camera, watchdog, all resources
     */
    fun forceStopAll() {
        isScanning = false
        
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
