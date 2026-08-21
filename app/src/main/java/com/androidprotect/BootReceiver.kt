package com.androidprotect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BootReceiver — reinicia o AntiTheftService após reinicialização.
 *
 * Compatível com:
 *  - Android padrão (BOOT_COMPLETED)
 *  - Android 7+ Direct Boot (LOCKED_BOOT_COMPLETED — dispara antes do unlock do usuário)
 *  - Xiaomi / MIUI (QUICKBOOT_POWERON)
 *  - Huawei / EMUI (HWBOOT_COMPLETED)
 *  - HTC (QUICKBOOT_POWERON)
 *  - Samsung OneUI / outros (USER_PRESENT — fallback no primeiro unlock pós-boot)
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "onReceive: action=$action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",   // Direct Boot (API 24+)
            "android.intent.action.QUICKBOOT_POWERON",        // Xiaomi / HTC
            "com.htc.intent.action.QUICKBOOT_POWERON",        // HTC legado
            "com.huawei.intent.action.HWBOOT_COMPLETED",      // Huawei / EMUI
            Intent.ACTION_USER_PRESENT -> {                   // Samsung / fallback: 1º unlock
                startProtectService(context)
                ServiceWatchdogWorker.schedule(context)
            }
            else -> {
                Log.d(TAG, "Ação não reconhecida ignorada: $action")
            }
        }
    }

    private fun startProtectService(context: Context) {
        val serviceIntent = Intent(context, AntiTheftService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "AntiTheftService iniciado com sucesso.")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar AntiTheftService: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
