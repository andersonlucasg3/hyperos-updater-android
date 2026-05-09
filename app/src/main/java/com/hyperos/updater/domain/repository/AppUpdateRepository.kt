package com.hyperos.updater.domain.repository

import com.hyperos.updater.domain.model.AppInfo
import com.hyperos.updater.domain.model.AppUpdate
import kotlinx.coroutines.flow.Flow

interface AppUpdateRepository {
    suspend fun getInstalledApps(appType: com.hyperos.updater.domain.model.AppType): List<AppInfo>
    fun checkSystemAppUpdates(): Flow<AppUpdate>
    fun checkThirdPartyAppUpdates(): Flow<AppUpdate>
}
