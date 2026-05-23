package com.androidprotect.helpers

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

class CameraHelper(private val context: Context) {

    fun takeBackgroundPhoto(
        lifecycleOwner: LifecycleOwner,
        useFrontCamera: Boolean,
        onComplete: (File?) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                // Configure camera selector (front or back)
                val cameraSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                
                // Configure ImageCapture
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                
                // Bind to background lifecycle
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageCapture
                )
                
                // Setup output file in cache
                val cacheDir = context.cacheDir
                val outputFile = File.createTempFile("photo_cap_", ".jpg", cacheDir)
                val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                
                // Capture Image
                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            Log.d("CameraHelper", "Photo saved successfully to ${outputFile.absolutePath}")
                            // Unbind cameras to release resources
                            cameraProvider.unbindAll()
                            onComplete(outputFile)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e("CameraHelper", "Failed to capture photo: ${exception.message}", exception)
                            cameraProvider.unbindAll()
                            onComplete(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("CameraHelper", "Camera provider error: ${e.message}", e)
                onComplete(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
