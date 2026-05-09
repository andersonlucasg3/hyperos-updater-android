package com.hyperos.updater.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hyperos.updater.domain.usecase.CheckSystemAppUpdatesUseCase
import com.hyperos.updater.domain.usecase.CheckThirdPartyAppUpdatesUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.count

@HiltWorker
class AppCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checkSystemAppUpdatesUseCase: CheckSystemAppUpdatesUseCase,
    private val checkThirdPartyAppUpdatesUseCase: CheckThirdPartyAppUpdatesUseCase,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            var systemCount = 0
            var thirdPartyCount = 0
            checkSystemAppUpdatesUseCase().collect { update ->
                if (update.currentVersion != update.latestVersion) systemCount++
            }
            checkThirdPartyAppUpdatesUseCase().collect { update ->
                if (update.currentVersion != update.latestVersion) thirdPartyCount++
            }
            val total = systemCount + thirdPartyCount
            if (total > 0) {
                notificationHelper.showAppUpdatesAvailable(total)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
