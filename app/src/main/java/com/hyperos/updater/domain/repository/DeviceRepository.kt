package com.hyperos.updater.domain.repository

import com.hyperos.updater.domain.model.DeviceInfo

interface DeviceRepository {
    suspend fun getDeviceInfo(): DeviceInfo
}
