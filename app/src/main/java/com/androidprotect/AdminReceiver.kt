package com.androidprotect

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.d("AdminReceiver", "Device Admin ENABLED — uninstall protection active")
        Toast.makeText(context, "Proteção de administrador ativada!", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w("AdminReceiver", "Device Admin DISABLED — protection removed!")
        // Alert the server that admin was revoked (possible theft indicator)
        context.getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("admin_enabled", false).apply()
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        Log.w("AdminReceiver", "Password attempt failed")
    }
}
