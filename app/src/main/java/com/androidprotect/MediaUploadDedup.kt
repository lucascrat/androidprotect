package com.androidprotect

import java.io.File

/**
 * Shared deduplication between WhatsAppMediaObserver (FileObserver) and
 * WhatsAppMediaStoreObserver (ContentObserver) so the same file isn't
 * uploaded twice.
 */
object MediaUploadDedup {
    private val uploaded = mutableSetOf<String>()

    /**
     * Returns true if this file should be uploaded (i.e. hasn't been
     * uploaded recently).  Once approved, the key is remembered so
     * subsequent calls return false.
     */
    fun shouldUpload(file: File): Boolean {
        val key = "${file.absolutePath}:${file.length()}"
        synchronized(uploaded) {
            if (uploaded.contains(key)) return false
            uploaded.add(key)
            if (uploaded.size > 500) uploaded.clear()
            return true
        }
    }
}
