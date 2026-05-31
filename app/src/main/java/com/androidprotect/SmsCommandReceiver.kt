package com.androidprotect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log

/**
 * Listens for incoming SMS containing secret commands.
 * Format: [PROTECT:COMMAND]  e.g. [PROTECT:PLAY_ALARM]
 * Works even without internet as a backup control channel.
 */
class SmsCommandReceiver : BroadcastReceiver() {

    companion object {
        private val COMMAND_REGEX = Regex("""\[PROTECT:(\w+)\]""")
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } else {
            @Suppress("DEPRECATION")
            (intent.extras?.get("pdus") as? Array<*>)
                ?.mapNotNull { android.telephony.SmsMessage.createFromPdu(it as ByteArray) }
                ?.toTypedArray() ?: emptyArray()
        } ?: return

        for (msg in messages) {
            val body = msg.messageBody ?: continue
            val match = COMMAND_REGEX.find(body) ?: continue
            val command = match.groupValues[1].uppercase()

            Log.i("SmsCommandReceiver", "SMS command received: $command from ${msg.originatingAddress}")

            // Forward to service via intent extra
            val svcIntent = Intent(context, AntiTheftService::class.java).apply {
                putExtra("SMS_COMMAND", command)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(svcIntent)
                else
                    context.startService(svcIntent)
            } catch (e: Exception) {
                Log.e("SmsCommandReceiver", "Failed to start service: ${e.message}")
            }
        }
    }
}
