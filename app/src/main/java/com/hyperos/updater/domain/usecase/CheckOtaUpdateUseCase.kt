package com.hyperos.updater.domain.usecase

import com.hyperos.updater.data.remote.MemeOsOtaService
import com.hyperos.updater.domain.model.OtaSource
import com.hyperos.updater.domain.model.OtaSourceInfo
import com.hyperos.updater.domain.model.OtaUpdate
import com.hyperos.updater.domain.model.UpdateState
import com.hyperos.updater.domain.repository.DeviceRepository
import com.hyperos.updater.domain.repository.OtaRepository
import com.hyperos.updater.util.VersionComparator
import javax.inject.Inject

class CheckOtaUpdateUseCase @Inject constructor(
    private val otaRepository: OtaRepository,
    private val deviceRepository: DeviceRepository,
    private val memeOsOtaService: MemeOsOtaService
) {
    suspend operator fun invoke(): UpdateState {
        val device = deviceRepository.getDeviceInfo()
        val branch = "F"
        val isGlobal = if (device.isGlobal) 1 else 0

        // Check Xiaomi API
        val xiaomiUpdate = otaRepository.checkForUpdate(
            codename = device.codename,
            branch = branch,
            region = device.region,
            currentVersion = device.miuiVersion,
            isGlobal = isGlobal
        )

        // Check MemeOs as additional source
        val memeOsResult = memeOsOtaService.checkUpdate(device.codename)
        val memeOsUpdate = if (memeOsResult != null) {
            OtaUpdate(
                version = memeOsResult.versionName,
                androidVersion = "",
                branch = branch,
                fileSize = 0L,
                md5 = null,
                changelog = null,
                downloadUrl = memeOsResult.downloadUrl,
                filename = null,
                publishedDate = null,
                source = OtaSource.MEMEOS
            )
        } else null

        // Collect source info
        val sources = mutableListOf<OtaSourceInfo>()
        if (xiaomiUpdate != null) {
            sources.add(OtaSourceInfo(OtaSource.XIAOMI_API, xiaomiUpdate.version, xiaomiUpdate.downloadUrl))
        }
        if (memeOsUpdate != null) {
            sources.add(OtaSourceInfo(OtaSource.MEMEOS, memeOsUpdate.version, memeOsUpdate.downloadUrl))
        }

        // Pick the newest version across sources
        val best = listOfNotNull(xiaomiUpdate, memeOsUpdate).maxWithOrNull { a, b ->
            if (VersionComparator.isNewer(a.version, b.version)) 1 else -1
        }

        return if (best != null && VersionComparator.isNewer(device.miuiVersion, best.version)) {
            UpdateState.Available(best.copy(otaSources = sources))
        } else {
            UpdateState.Idle
        }
    }
}
