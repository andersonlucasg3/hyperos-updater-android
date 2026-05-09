package com.hyperos.updater

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.hyperos.updater.util.ShizukuHelper
import com.hyperos.updater.worker.WorkerScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HyperOsApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        ShizukuHelper.init()
        createNotificationChannels()
        WorkerScheduler.scheduleAll(this)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val otaChannel = NotificationChannel(
            CHANNEL_OTA,
            getString(R.string.ota_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.ota_channel_desc)
        }
        manager.createNotificationChannel(otaChannel)

        val appChannel = NotificationChannel(
            CHANNEL_APP_UPDATES,
            getString(R.string.app_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.app_channel_desc)
        }
        manager.createNotificationChannel(appChannel)
    }

    companion object {
        const val CHANNEL_OTA = "ota_updates"
        const val CHANNEL_APP_UPDATES = "app_updates"
    }
}
