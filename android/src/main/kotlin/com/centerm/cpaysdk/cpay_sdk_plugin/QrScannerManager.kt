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
 * 
 * Key design decisions:
 * - No Thread.sleep() on main thread (blocks CameraX frame delivery)
 * - imageProxy.close() guaranteed in ALL code paths (prevents frame starvation)
 * - BarcodeScanner reused across restarts (avoids re-init overhead)
 * - Low resolution + frame skipping for long-running stability
 */
class QrScannerManager(private val context: Context) {

    companion object {
        private const val TAG = "QrScannerManager"
        private const val WATCHDOG_INTERVAL_MS = 10000L       // Check every 10 seconds  
        private const val FRAME_TIMEOUT_MS = 20000L           // Restart if no frame for 20 seconds
        private const val DEFAULT_PERIODIC_RESTART_MS = 60000L // Default: restart every 60s
        private const val PROCESS_EVERY_N_FRAMES = 3          // Process every 3rd frame
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService? = null
    
    // Reuse barcode scanner - creating/destroying causes memory churn
    private var barcodeScanner: BarcodeScanner? = null
    
    private var resultListener: ((String) -> Unit)? = null
    private var debugListener: ((String) -> Unit)? = null
    private var errorListener: ((String) -> Unit)? = null
    private var lastScannedCode: String? = null
    private var lastScanTime: Long = 0
    private val debounceMs = 1500L
    
    @Volatile private var isScanning = false
    private var frameCount = 0
    private val isProcessing = AtomicBoolean(false)

    // Watchdog
    private var watchdogScheduler: ScheduledExecutorService? = null
    @Volatile private var lastFrameTime: Long = 0
    @Volatile private var cameraStartTime: Long = 0  // Track when camera session started
    private var periodicRestartMs: Long = DEFAULT_PERIODIC_RESTART_MS  // Configurable restart interval
    private var savedLifecycleOwner: LifecycleOwner? = null
    private var savedUseFrontCamera: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setResultListener(listener: (String) -> Unit) { resultListener = listener }
    fun setDebugListener(listener: (String) -> Unit) { debugListener = listener }
    fun setErrorListener(listener: (String) -> Unit) { errorListener = listener }

    private fun logDebug(msg: String) {
        Log.d(TAG, msg)
        debugListener?.invoke("[QR] $msg")
    }

    fun startScanning(
        lifecycleOwner: LifecycleOwner, 
        useFrontCamera: Boolean = false,
        periodicRestartIntervalMs: Long = DEFAULT_PERIODIC_RESTART_MS
    ) {
        savedLifecycleOwner = lifecycleOwner
        savedUseFrontCamera = useFrontCamera
        periodicRestartMs = periodicRestartIntervalMs
        
        releaseCamera()
        
        mainHandler.postDelayed({
            openCamera(lifecycleOwner, useFrontCamera)
        }, 300)
    }

    private fun releaseCamera() {
        logDebug("Releasing camera resources...")
        isScanning = false
        isProcessing.set(false)
        stopWatchdog()
        
        try { imageAnalysis?.clearAnalyzer() } catch (e: Exception) { }
        try { cameraProvider?.unbindAll() } catch (e: Exception) { }
        try { cameraExecutor?.shutdownNow() } catch (e: Exception) { }
        // Close scanner on each release - create fresh one on restart
        try { barcodeScanner?.close() } catch (e: Exception) { }
        
        imageAnalysis = null
        cameraExecutor = null
        barcodeScanner = null
        lastScannedCode = null
        
        Log.d(TAG, "Camera resources released")
    }

    private fun openCamera(lifecycleOwner: LifecycleOwner, useFrontCamera: Boolean) {
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Create FRESH scanner each time (avoid stale scanner after restart)
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
        val scanner = BarcodeScanning.getClient(options)
        barcodeScanner = scanner

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                cameraProvider?.unbindAll()

                // Use low resolution for stability on K9
                imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER)
                    .build()

                // Set analyzer BEFORE bind (critical for K9)
                val exec = cameraExecutor
                if (exec != null && !exec.isShutdown) {
                    imageAnalysis?.setAnalyzer(exec) { imageProxy ->
                        processImage(imageProxy, scanner)
                    }
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
                logDebug("Camera started (${if (useFrontCamera) "Front" else "Back"})")

                startWatchdog()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                val errorMsg = e.message ?: ""
                logDebug("CAMERA ERROR: $errorMsg")
                if (errorMsg.contains("MAX_CAMERAS_IN_USE") || 
                    errorMsg.contains("CAMERA_IN_USE") ||
                    errorMsg.contains("Camera is being used")) {
                    errorListener?.invoke("Camera locked - please restart device")
                } else {
                    errorListener?.invoke("Camera error: $errorMsg")
                }
                releaseCamera()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Watchdog - checks for:
     * 1. Camera freeze (no frames for FRAME_TIMEOUT_MS)
     * 2. Periodic restart (configurable interval) - K9 workaround
     */
    private fun startWatchdog() {
        stopWatchdog()
        
        cameraStartTime = System.currentTimeMillis()
        
        watchdogScheduler = Executors.newSingleThreadScheduledExecutor()
        watchdogScheduler?.scheduleAtFixedRate({
            try {
                if (!isScanning) return@scheduleAtFixedRate
                
                val now = System.currentTimeMillis()
                val timeSinceLastFrame = now - lastFrameTime
                val timeSinceStart = now - cameraStartTime
                
                var shouldRestart = false
                var reason = ""
                
                // Check 1: Frame timeout (camera frozen)
                if (timeSinceLastFrame > FRAME_TIMEOUT_MS) {
                    shouldRestart = true
                    reason = "No frames for ${timeSinceLastFrame}ms"
                }
                
                // Check 2: Periodic restart (K9 workaround)
                if (timeSinceStart > periodicRestartMs) {
                    shouldRestart = true
                    reason = "Periodic restart (${timeSinceStart}ms elapsed)"
                }
                
                if (shouldRestart) {
                    Log.w(TAG, "Watchdog: $reason - restarting...")
                    logDebug("Watchdog: $reason")
                    
                    mainHandler.post {
                        val lo = savedLifecycleOwner
                        if (lo != null) {
                            releaseCamera()
                            mainHandler.postDelayed({
                                openCamera(lo, savedUseFrontCamera)
                            }, 500)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog error", e)
            }
        }, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS)
        
        Log.d(TAG, "Watchdog started (freeze: ${FRAME_TIMEOUT_MS}ms, periodic: ${periodicRestartMs}ms)")
    }

    private fun stopWatchdog() {
        try { watchdogScheduler?.shutdownNow() } catch (e: Exception) { }
        watchdogScheduler = null
    }

    /**
     * Process camera frame. CRITICAL: imageProxy.close() MUST be called in ALL paths.
     * If imageProxy is not closed, CameraX stops delivering frames permanently.
     */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy, scanner: BarcodeScanner) {
        // Guard: not scanning
        if (!isScanning) {
            imageProxy.close()
            return
        }
        
        // Update watchdog timestamp FIRST
        lastFrameTime = System.currentTimeMillis()
        frameCount++
        
        // Skip frames to reduce CPU load
        if (frameCount % PROCESS_EVERY_N_FRAMES != 0) {
            imageProxy.close()
            return
        }
        
        // Prevent concurrent processing
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        
        if (frameCount % 150 == 0) {
            logDebug("Frame #$frameCount")
        }
        
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            isProcessing.set(false)
            return
        }
        
        // Wrap EVERYTHING in try-catch to guarantee imageProxy.close()
        try {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val task = scanner.process(image)
            
            // CRITICAL: task should never be null, but guard anyway
            task.addOnSuccessListener { barcodes ->
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
                .addOnFailureListener { e ->
                    Log.e(TAG, "Barcode scan failed: ${e.message}")
                }
                .addOnCompleteListener {
                    // This is the NORMAL path for closing
                    imageProxy.close()
                    isProcessing.set(false)
                }
        } catch (e: Exception) {
            // FALLBACK: if scanner.process() throws, close here
            Log.e(TAG, "processImage exception - closing proxy", e)
            imageProxy.close()
            isProcessing.set(false)
        }
    }

    fun forceStopAll() {
        releaseCamera()
        // Close scanner only on full stop
        try { barcodeScanner?.close() } catch (e: Exception) { }
        barcodeScanner = null
        cameraProvider = null
    }

    fun stopScanning() {
        logDebug("Stopping scanner...")
        savedLifecycleOwner = null
        mainHandler.removeCallbacksAndMessages(null)
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
