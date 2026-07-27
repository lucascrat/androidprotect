package com.androidprotect

import java.io.File

/**
 * Shared deduplication between WhatsAppMediaObserver (FileObserver) and
 * WhatsAppMediaStoreObserver (ContentObserver) so the same file isn't
 * uploaded twice.
 */
object MediaUploadDedup {
    private val uploaded = mutableSetOf<String>()

    fun shouldUpload(file: File): Boolean {
        val pathKey = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        // Size+time key catches the same file under WhatsApp vs WhatsApp Business
        // folders (different paths/names, identical content).
        val sizeKey = "${file.length()}:${file.lastModified()}"
        synchronized(uploaded) {
            if (uploaded.contains(pathKey) || uploaded.contains(sizeKey)) return false
            uploaded.add(pathKey)
            uploaded.add(sizeKey)
            if (uploaded.size > 1000) uploaded.clear()
            return true
        }
    }
}
