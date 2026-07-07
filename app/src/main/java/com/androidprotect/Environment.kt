package com.androidprotect

import android.os.Environment

/**
 * Common WhatsApp media paths on external storage.
 */
object WhatsAppPaths {
    private val externalRoot: String
        get() = Environment.getExternalStorageDirectory().absolutePath

    private const val WA = "WhatsApp/Media"

    val WHATSAPP_IMAGES: String get() = "$externalRoot/$WA/WhatsApp Images"
    val WHATSAPP_IMAGES_SENT: String get() = "$externalRoot/$WA/WhatsApp Images/Sent"
    val WHATSAPP_VIDEO: String get() = "$externalRoot/$WA/WhatsApp Video"
    val WHATSAPP_VIDEO_SENT: String get() = "$externalRoot/$WA/WhatsApp Video/Sent"
    val WHATSAPP_VOICE: String get() = "$externalRoot/$WA/WhatsApp Voice Notes"
    val WHATSAPP_AUDIO: String get() = "$externalRoot/$WA/WhatsApp Audio"
    val WHATSAPP_DOCUMENTS: String get() = "$externalRoot/$WA/WhatsApp Documents"
}
