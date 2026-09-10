package com.androidprotect

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receptor de alarme Doze-proof que mantém o serviço vivo.
 *
 * Usa setExactAndAllowWhileIdle (RTC_WAKEUP) — dispara mesmo com a tela
 * apagada e no modo Doze do Android. A cada 5 minutos verifica se o serviço
 * está rodando e se o WebSocket está conectado, agindo conforme necessário.
 *
 * O WorkManager (15 min) + este alarme (5 min) formam duas camadas de proteção:
 *  - Alarme: reação rápida, dispara no Doze
 *  - WorkManager: fallback se o alarme for cancelado pelo sistema
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Alarm fired — service=${AntiTheftService.isServiceRunning} ws=${AntiTheftService.isWebSocketConnected}")

        when {
            // Serviço morreu — reinicia
            !AntiTheftService.isServiceRunning -> {
                Log.w(TAG, "Service dead — restarting")
                val svcIntent = Intent(context, AntiTheftService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        context.startForegroundService(svcIntent)
                    else
                        context.startService(svcIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service: ${e.message}")
                }
            }
            // Serviço vivo mas WebSocket caiu — reconecta
            !AntiTheftService.isWebSocketConnected -> {
                Log.w(TAG, "WS disconnected — triggering reconnect")
                val svcIntent = Intent(context, AntiTheftService::class.java)
                    .putExtra("KEEPALIVE_RECONNECT", true)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        context.startForegroundService(svcIntent)
                    else
                        context.startService(svcIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to trigger reconnect: ${e.message}")
                }
            }
            else -> Log.d(TAG, "Service and WS healthy — nothing to do")
        }

        // Sempre reagenda o próximo alarme
        schedule(context)
    }

    companion object {
        private const val TAG           = "KeepAliveReceiver"
        private const val REQUEST_CODE  = 0x4B41 // "KA"
        private const val ACTION        = "com.androidprotect.KEEPALIVE"
        const val  INTERVAL_MS          = 5 * 60 * 1000L // 5 minutos

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(context) ?: return
            val triggerAt = System.currentTimeMillis() + INTERVAL_MS
            try {
                // setExactAndAllowWhileIdle dispara mesmo durante Doze (RTC_WAKEUP acorda a CPU)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
                Log.d(TAG, "KeepAlive alarm set in ${INTERVAL_MS / 1000}s")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule alarm: ${e.message}")
            }
        }

        /** Alarme imediato — usado pelo onTaskRemoved para reagir em ~3 segundos */
        fun scheduleImmediate(context: Context, delayMs: Long = 3_000L) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(context) ?: return
            val triggerAt = System.currentTimeMillis() + delayMs
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
                Log.d(TAG, "Immediate alarm set in ${delayMs}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule immediate alarm: ${e.message}")
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                Intent(context, KeepAliveReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                am.cancel(pi)
                Log.d(TAG, "KeepAlive alarm cancelled")
            }
        }

        private fun pendingIntent(context: Context) = try {
            PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                Intent(context, KeepAliveReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create PendingIntent: ${e.message}")
            null
        }
    }
}
