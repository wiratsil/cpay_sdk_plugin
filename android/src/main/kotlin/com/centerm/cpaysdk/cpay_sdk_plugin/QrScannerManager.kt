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
 * Includes fail-fast logic for ERROR_MAX_CAMERAS_IN_USE - no infinite retry.
 */
class QrScannerManager(private val context: Context) {

    companion object {
        private const val TAG = "QrScannerManager"
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
    private var triedFallbackCamera = false

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
        triedFallbackCamera = false
        startScanningInternal(lifecycleOwner, useFrontCamera)
    }

    private fun startScanningInternal(lifecycleOwner: LifecycleOwner, useFrontCamera: Boolean) {
        // Force stop first to release any locked camera
        logDebug("Releasing previous camera resources...")
        forceStopAll()
        
        // Wait for resources to be released
        Thread.sleep(300)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Configure barcode scanner
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

                // Force unbind all first
                try {
                    cameraProvider?.unbindAll()
                    Thread.sleep(100)
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

                // Bind to lifecycle
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis
                )

                isScanning = true
                frameCount = 0
                logDebug("Camera started successfully (${if (useFrontCamera) "Front" else "Back"})")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                val errorMsg = e.message ?: ""
                
                // Fail gracefully - no retry, just report error
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

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        if (!isScanning) {
            imageProxy.close()
            return
        }
        
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
     * Force stop everything - use before starting and on app close
     */
    fun forceStopAll() {
        isScanning = false
        
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
