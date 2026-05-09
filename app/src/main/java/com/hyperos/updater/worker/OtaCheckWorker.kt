package com.hyperos.updater.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hyperos.updater.domain.model.UpdateState
import com.hyperos.updater.domain.usecase.CheckOtaUpdateUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class OtaCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checkOtaUpdateUseCase: CheckOtaUpdateUseCase,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val state = checkOtaUpdateUseCase()
            if (state is UpdateState.Available) {
                notificationHelper.showOtaUpdateAvailable(
                    state.update.version,
                    state.update.changelog
                )
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
