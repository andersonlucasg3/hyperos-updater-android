package com.hyperos.updater.domain.usecase

import com.hyperos.updater.domain.model.DeviceInfo
import com.hyperos.updater.domain.repository.DeviceRepository
import javax.inject.Inject

class GetDeviceInfoUseCase @Inject constructor(
    private val deviceRepository: DeviceRepository
) {
    suspend operator fun invoke(): DeviceInfo = deviceRepository.getDeviceInfo()
}
