package com.androidprotect

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class ServiceWatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        if (!AntiTheftService.isServiceRunning) {
            Log.w("ServiceWatchdog", "Service not running — restarting...")
            val intent = Intent(applicationContext, AntiTheftService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    applicationContext.startForegroundService(intent)
                else
                    applicationContext.startService(intent)
                Result.success()
            } catch (e: SecurityException) {
                // Missing permission — don't retry, wait for next periodic run
                Log.e("ServiceWatchdog", "SecurityException restarting service: ${e.message}")
                Result.success()
            } catch (e: Exception) {
                Log.e("ServiceWatchdog", "Failed to restart: ${e.message}")
                Result.retry()
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_TAG = "service_watchdog"

        fun scheduleImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<ServiceWatchdogWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).addTag(WORK_TAG).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d("ServiceWatchdog", "Watchdog scheduled every 15 minutes")
        }
    }
}
