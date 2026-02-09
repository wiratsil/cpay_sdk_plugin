package com.centerm.cpaysdk.cpay_sdk_plugin

import android.content.Context
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

/**
 * Manages background QR/Barcode scanning using CameraX + ML Kit.
 * No camera preview UI is shown.
 * Includes robust camera release and retry logic for ERROR_MAX_CAMERAS_IN_USE.
 */
class QrScannerManager(private val context: Context) {

    companion object {
        private const val TAG = "QrScannerManager"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 500L
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService? = null
    private var barcodeScanner: BarcodeScanner? = null
    
    private var resultListener: ((String) -> Unit)? = null
    private var debugListener: ((String) -> Unit)? = null
    private var lastScannedCode: String? = null
    private var lastScanTime: Long = 0
    private val debounceMs = 1500L // Prevent duplicate scans within 1.5s
    
    private var isScanning = false
    private var frameCount = 0
    private var retryCount = 0

    fun setResultListener(listener: (String) -> Unit) {
        resultListener = listener
    }

    fun setDebugListener(listener: (String) -> Unit) {
        debugListener = listener
    }

    private fun logDebug(msg: String) {
        Log.d(TAG, msg)
        debugListener?.invoke("[QR] $msg")
    }

    fun startScanning(lifecycleOwner: LifecycleOwner, useFrontCamera: Boolean = false) {
        retryCount = 0
        startScanningInternal(lifecycleOwner, useFrontCamera)
    }

    private fun startScanningInternal(lifecycleOwner: LifecycleOwner, useFrontCamera: Boolean) {
        // Force stop first to release any locked camera
        logDebug("Force stopping previous session...")
        forceStopAll()
        
        // Delay to ensure resources are fully released
        Thread.sleep(500)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Configure barcode scanner for QR codes and common barcodes
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

                // Force unbind all before binding new use cases
                try {
                    cameraProvider?.unbindAll()
                } catch (e: Exception) {
                    Log.w(TAG, "Error unbinding: ${e.message}")
                }

                // Image Analysis use case
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis?.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    processImage(imageProxy)
                }

                // Select camera
                val cameraSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                // Bind to lifecycle (headless - no preview)
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis
                )

                isScanning = true
                frameCount = 0
                retryCount = 0
                logDebug("Camera started (${if (useFrontCamera) "Front" else "Back"})")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                val errorMsg = e.message ?: ""
                
                // Check if it's MAX_CAMERAS_IN_USE error
                if (errorMsg.contains("MAX_CAMERAS_IN_USE") || 
                    errorMsg.contains("CAMERA_IN_USE") ||
                    errorMsg.contains("Camera is being used")) {
                    
                    if (retryCount < MAX_RETRY_ATTEMPTS) {
                        retryCount++
                        logDebug("Camera in use, retry attempt $retryCount/$MAX_RETRY_ATTEMPTS...")
                        
                        // Wait longer and retry
                        Thread {
                            Thread.sleep(RETRY_DELAY_MS * retryCount)
                            ContextCompat.getMainExecutor(context).execute {
                                startScanningInternal(lifecycleOwner, useFrontCamera)
                            }
                        }.start()
                    } else {
                        logDebug("Camera start FAILED after $MAX_RETRY_ATTEMPTS retries: $errorMsg")
                    }
                } else {
                    logDebug("Camera start FAILED: $errorMsg")
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        frameCount++
        // Log every 30 frames to show camera is working
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
                            // Debounce: skip if same code scanned recently
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
     * Force stop everything - use before starting and on app close
     */
    fun forceStopAll() {
        isScanning = false
        
        // Clear analyzer first
        try {
            imageAnalysis?.clearAnalyzer()
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing analyzer: ${e.message}")
        }
        
        // Unbind all cameras
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding: ${e.message}")
        }
        
        // Shutdown executor
        try {
            cameraExecutor?.shutdownNow()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down executor: ${e.message}")
        }
        
        // Close barcode scanner
        try {
            barcodeScanner?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing scanner: ${e.message}")
        }
        
        // Clear all references
        cameraProvider = null
        imageAnalysis = null
        cameraExecutor = null
        barcodeScanner = null
        lastScannedCode = null
        
        Log.d(TAG, "Force stopped all camera resources")
    }

    fun stopScanning() {
        forceStopAll()
    }

    fun isCurrentlyScanning(): Boolean = isScanning

    fun forceReleaseCamera() {
        try {
            cameraProvider?.unbindAll()
            imageAnalysis?.clearAnalyzer()
        } catch (e: Exception) {
            // ignore
        }
    }
}
