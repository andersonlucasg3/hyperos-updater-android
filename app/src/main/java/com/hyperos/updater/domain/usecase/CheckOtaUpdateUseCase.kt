package com.hyperos.updater.domain.usecase

import com.hyperos.updater.domain.model.UpdateState
import com.hyperos.updater.domain.repository.DeviceRepository
import com.hyperos.updater.domain.repository.OtaRepository
import com.hyperos.updater.util.VersionComparator
import javax.inject.Inject

class CheckOtaUpdateUseCase @Inject constructor(
    private val otaRepository: OtaRepository,
    private val deviceRepository: DeviceRepository
) {
    suspend operator fun invoke(): UpdateState {
        val device = deviceRepository.getDeviceInfo()

        val branch = "F"
        val isGlobal = if (device.isGlobal) 1 else 0

        val update = otaRepository.checkForUpdate(
            codename = device.codename,
            branch = branch,
            region = device.region,
            currentVersion = device.miuiVersion,
            isGlobal = isGlobal
        )

        return if (update != null && VersionComparator.isNewer(device.miuiVersion, update.version)) {
            UpdateState.Available(update)
        } else {
            UpdateState.Idle
        }
    }
}
