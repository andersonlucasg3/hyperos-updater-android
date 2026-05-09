package com.hyperos.updater.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hyperos.updater.HyperOsApp
import com.hyperos.updater.R
import com.hyperos.updater.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun showOtaUpdateAvailable(version: String, changelog: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("screen", "ota")
        }
        val pending = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, HyperOsApp.CHANNEL_OTA)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("System Update Available")
            .setContentText("HyperOS $version is available")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(changelog ?: "Tap to view details and download")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(OTA_NOTIFICATION_ID, notification)
    }

    fun showAppUpdatesAvailable(count: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("screen", "system_apps")
        }
        val pending = PendingIntent.getActivity(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, HyperOsApp.CHANNEL_APP_UPDATES)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("App Updates Available")
            .setContentText("$count app(s) can be updated")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(APP_NOTIFICATION_ID, notification)
    }

    companion object {
        const val OTA_NOTIFICATION_ID = 100
        const val APP_NOTIFICATION_ID = 200
    }
}
