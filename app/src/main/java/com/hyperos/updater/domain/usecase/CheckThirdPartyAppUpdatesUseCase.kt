package com.hyperos.updater.domain.usecase

import com.hyperos.updater.domain.model.AppUpdate
import com.hyperos.updater.domain.repository.AppUpdateRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class CheckThirdPartyAppUpdatesUseCase @Inject constructor(
    private val appUpdateRepository: AppUpdateRepository
) {
    operator fun invoke(): Flow<AppUpdate> = appUpdateRepository.checkThirdPartyAppUpdates()
}
