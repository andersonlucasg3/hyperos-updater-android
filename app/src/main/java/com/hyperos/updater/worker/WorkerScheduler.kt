package com.hyperos.updater.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkerScheduler {
    fun scheduleAll(context: Context) {
        // Cancel OTA check work from older builds (OTA tab removed in v1)
        WorkManager.getInstance(context).cancelUniqueWork("ota_check")

        val appRequest = PeriodicWorkRequestBuilder<AppCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("app_check", ExistingPeriodicWorkPolicy.KEEP, appRequest)
    }
}
