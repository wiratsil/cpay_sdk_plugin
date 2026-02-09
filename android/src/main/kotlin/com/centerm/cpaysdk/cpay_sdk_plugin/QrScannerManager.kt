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
    private var lastScannedCode: String? = null
    private var lastScanTime: Long = 0
    private val debounceMs = 1500L // Prevent duplicate scans within 1.5s
    
    private var isScanning = false
    private var frameCount = 0

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
        // Force release camera first
        if (isScanning || cameraProvider != null) {
            logDebug("Force releasing previous camera...")
            stopScanning()
        }

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

                // Unbind previous use cases
                cameraProvider?.unbindAll()

                // Bind to lifecycle (headless - no preview)
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis
                )

                isScanning = true
                frameCount = 0
                logDebug("Camera started (${if (useFrontCamera) "Front" else "Back"})")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                logDebug("Camera start FAILED: ${e.message}")
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

    fun stopScanning() {
        isScanning = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageAnalysis = null
        cameraExecutor?.shutdown()
        cameraExecutor = null
        barcodeScanner?.close()
        barcodeScanner = null
        lastScannedCode = null
        Log.d(TAG, "Background QR scanning stopped")
    }

    fun isCurrentlyScanning(): Boolean = isScanning
}
