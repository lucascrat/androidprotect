package com.androidprotect

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * BootReceiver — reinicia o AntiTheftService após reinicialização.
 *
 * Compatível com:
 *  - Android padrão (BOOT_COMPLETED)
 *  - Android 7+ Direct Boot (LOCKED_BOOT_COMPLETED — dispara antes do unlock do usuário)
 *  - Xiaomi / MIUI / HyperOS / Poco / Redmi (QUICKBOOT_POWERON)
 *  - Huawei / EMUI / HarmonyOS (HWBOOT_COMPLETED)
 *  - HTC (QUICKBOOT_POWERON)
 *  - LG (com.lge.lgps.QUICKBOOT_POWERON)
 *  - Meizu / Flyme (com.meizu.intent.action.QUICK_BOOT)
 *  - Sony / Xperia (com.sonyericsson.intent.action.BOOT_COMPLETED)
 *  - Samsung OneUI / outros (USER_PRESENT — fallback no primeiro unlock pós-boot)
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "onReceive: action=$action")

        when (action) {
            // Ao atualizar o próprio app: re-habilita o alias do launcher se o usuário
            // não o tiver ocultado manualmente. Isso resolve o caso em que a versão
            // anterior ocultou o ícone automaticamente e o instalador não consegue
            // abrir o app porque o alias está desabilitado.
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                restoreLauncherAliasIfNeeded(context)
            }
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",        // Direct Boot (API 24+)
            "android.intent.action.QUICKBOOT_POWERON",             // Xiaomi / HTC
            "com.htc.intent.action.QUICKBOOT_POWERON",             // HTC legado
            "com.huawei.intent.action.HWBOOT_COMPLETED",           // Huawei / EMUI
            "com.lge.lgps.QUICKBOOT_POWERON",                      // LG
            "com.meizu.intent.action.QUICK_BOOT",                  // Meizu / Flyme
            "com.sonyericsson.intent.action.BOOT_COMPLETED",       // Sony / Xperia
            "com.sony.msm.intent.action.BOOT_COMPLETED",           // Sony MSM
            Intent.ACTION_USER_PRESENT -> {                        // Samsung / fallback: 1º unlock
                restoreLauncherAliasIfNeeded(context)
                startProtectService(context)
                ServiceWatchdogWorker.schedule(context)
            }
            else -> {
                Log.d(TAG, "Ação não reconhecida ignorada: $action")
            }
        }
    }

    private fun restoreLauncherAliasIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("icon_hidden_by_user", false)) return
        try {
            val cn = ComponentName(context.packageName, "${context.packageName}.MainActivityAlias")
            val state = context.packageManager.getComponentEnabledSetting(cn)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                context.packageManager.setComponentEnabledSetting(
                    cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
                )
                Log.d(TAG, "Alias do launcher reabilitado pelo BootReceiver.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao reabilitar alias do launcher: ${e.message}")
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
