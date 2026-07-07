package com.androidprotect

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

class ProtectAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.d("AccessibilityService", "Connected — screen capture now permanent")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreen(callback: (Bitmap?) -> Unit) {
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer,
                            result.colorSpace
                        )
                        result.hardwareBuffer.close()
                        callback(bitmap)
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.w("AccessibilityService", "Screenshot failed: $errorCode")
                        callback(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("AccessibilityService", "captureScreen error: ${e.message}")
            callback(null)
        }
    }

    companion object {
        @Volatile var instance: ProtectAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return flat.contains("${context.packageName}/${ProtectAccessibilityService::class.java.name}")
        }
    }
}
