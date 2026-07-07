package com.androidprotect

import android.os.Build
import android.os.Environment
import java.io.File

/**
 * WhatsApp media paths — handles both legacy and Android 11+ scoped storage.
 */
object WhatsAppPaths {
    private val externalRoot: String
        get() = Environment.getExternalStorageDirectory().absolutePath

    private const val LEGACY = "WhatsApp/Media"
    private const val SCOPED = "Android/media/com.whatsapp/WhatsApp/Media"
    private const val SCOPED_BIZ = "Android/media/com.whatsapp.w4b/WhatsApp Business/Media"

    private fun resolve(vararg candidates: String): String {
        for (path in candidates) {
            val dir = File(externalRoot, path)
            if (dir.exists() && dir.isDirectory) return dir.absolutePath
        }
        // Fallback to first candidate
        return File(externalRoot, candidates[0]).absolutePath
    }

    private fun resolveAll(vararg candidates: String): List<String> {
        return candidates.map { File(externalRoot, it).absolutePath }.filter { File(it).exists() }
    }

    val WHATSAPP_IMAGES: String get() = resolve(
        "$SCOPED/WhatsApp Images",
        "$LEGACY/WhatsApp Images",
        "$SCOPED_BIZ/WhatsApp Business Images"
    )
    val WHATSAPP_IMAGES_SENT: String get() = resolve(
        "$SCOPED/WhatsApp Images/Sent",
        "$LEGACY/WhatsApp Images/Sent",
        "$SCOPED_BIZ/WhatsApp Business Images/Sent"
    )
    val WHATSAPP_VIDEO: String get() = resolve(
        "$SCOPED/WhatsApp Video",
        "$LEGACY/WhatsApp Video",
        "$SCOPED_BIZ/WhatsApp Business Video"
    )
    val WHATSAPP_VIDEO_SENT: String get() = resolve(
        "$SCOPED/WhatsApp Video/Sent",
        "$LEGACY/WhatsApp Video/Sent",
        "$SCOPED_BIZ/WhatsApp Business Video/Sent"
    )
    val WHATSAPP_VOICE: String get() = resolve(
        "$SCOPED/WhatsApp Voice Notes",
        "$LEGACY/WhatsApp Voice Notes",
        "$SCOPED_BIZ/WhatsApp Business Voice Notes"
    )
    val WHATSAPP_AUDIO: String get() = resolve(
        "$SCOPED/WhatsApp Audio",
        "$LEGACY/WhatsApp Audio",
        "$SCOPED_BIZ/WhatsApp Business Audio"
    )
    val WHATSAPP_DOCUMENTS: String get() = resolve(
        "$SCOPED/WhatsApp Documents",
        "$LEGACY/WhatsApp Documents",
        "$SCOPED_BIZ/WhatsApp Business Documents"
    )

    fun allImageFolders(): List<String> = resolveAll(
        "$SCOPED/WhatsApp Images",
        "$LEGACY/WhatsApp Images",
        "$SCOPED_BIZ/WhatsApp Business Images"
    )
    fun allVideoFolders(): List<String> = resolveAll(
        "$SCOPED/WhatsApp Video",
        "$LEGACY/WhatsApp Video",
        "$SCOPED_BIZ/WhatsApp Business Video"
    )
    fun allAudioFolders(): List<String> = resolveAll(
        "$SCOPED/WhatsApp Voice Notes",
        "$LEGACY/WhatsApp Voice Notes",
        "$SCOPED/WhatsApp Audio",
        "$LEGACY/WhatsApp Audio",
        "$SCOPED_BIZ/WhatsApp Business Voice Notes",
        "$SCOPED_BIZ/WhatsApp Business Audio"
    )
    fun allDocumentFolders(): List<String> = resolveAll(
        "$SCOPED/WhatsApp Documents",
        "$LEGACY/WhatsApp Documents",
        "$SCOPED_BIZ/WhatsApp Business Documents"
    )

    fun logResolvedPaths() {
        println("WHATSAPP PATHS:")
        println("  Images:     $WHATSAPP_IMAGES")
        println("  ImagesSent: $WHATSAPP_IMAGES_SENT")
        println("  Video:      $WHATSAPP_VIDEO")
        println("  VideoSent:  $WHATSAPP_VIDEO_SENT")
        println("  Voice:      $WHATSAPP_VOICE")
        println("  Audio:      $WHATSAPP_AUDIO")
        println("  Documents:  $WHATSAPP_DOCUMENTS")
    }
}
