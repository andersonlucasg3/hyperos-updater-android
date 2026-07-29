package com.hyperos.updater.domain.repository

import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    val checkIntervalHours: Flow<Int>
    val autoUpdateEnabled: Flow<Boolean>
    suspend fun setCheckIntervalHours(hours: Int)
    suspend fun setAutoUpdateEnabled(enabled: Boolean)
    suspend fun addToBlacklist(packageName: String)
    suspend fun removeFromBlacklist(packageName: String)
    fun isBlacklisted(packageName: String): Flow<Boolean>
    val blacklistedPackages: Flow<Set<String>>
    val skippedVersions: Flow<Set<String>>
    suspend fun skipVersion(packageName: String, versionName: String)
    suspend fun removeSkippedVersion(packageName: String, versionName: String)
    fun isSkippedVersion(packageName: String, versionName: String): Flow<Boolean>
    val updatableFilterEnabled: Flow<Boolean>
    suspend fun setUpdatableFilterEnabled(enabled: Boolean)
    val showSystemApps: Flow<Boolean>
    suspend fun setShowSystemApps(show: Boolean)
}
