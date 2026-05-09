package com.hyperos.updater.domain.repository

import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    val shizukuEnabled: Flow<Boolean>
    val checkIntervalHours: Flow<Int>
    suspend fun setShizukuEnabled(enabled: Boolean)
    suspend fun setCheckIntervalHours(hours: Int)
    suspend fun addToBlacklist(packageName: String)
    suspend fun removeFromBlacklist(packageName: String)
    fun isBlacklisted(packageName: String): Flow<Boolean>
    val blacklistedPackages: Flow<Set<String>>
}
