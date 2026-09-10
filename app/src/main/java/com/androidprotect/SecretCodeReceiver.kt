package com.androidprotect

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Listens for the secret dial code *#*#7777#*#*.
 * When triggered, re-enables the launcher alias and opens the app.
 *
 * Note: we re-enable the ALIAS (MainActivityAlias), not the Activity itself.
 * The Activity is never disabled — only the alias that appears in the launcher
 * is toggled. This is the correct approach for Samsung OneUI and all other OEMs.
 */
class SecretCodeReceiver : BroadcastReceiver() {

    /** Must match the alias android:name in AndroidManifest.xml */
    private val LAUNCHER_ALIAS = "com.androidprotect.MainActivityAlias"

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("SecretCodeReceiver", "Secret code triggered — restoring launcher alias")

        // Re-enable the launcher alias so the icon reappears in the app drawer
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, LAUNCHER_ALIAS),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.e("SecretCodeReceiver", "Failed to enable launcher alias: ${e.message}")
        }

        // Launch MainActivity explicitly (always works — Activity is never disabled)
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(launch)
    }
}
