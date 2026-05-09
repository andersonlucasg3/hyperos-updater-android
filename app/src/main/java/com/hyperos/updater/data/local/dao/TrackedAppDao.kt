package com.hyperos.updater.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hyperos.updater.data.local.entity.TrackedAppEntity

@Dao
interface TrackedAppDao {
    @Query("SELECT * FROM tracked_apps WHERE appType = :type ORDER BY appName ASC")
    suspend fun getByType(type: String): List<TrackedAppEntity>

    @Query("SELECT * FROM tracked_apps WHERE packageName = :pkg")
    suspend fun getByPackage(pkg: String): TrackedAppEntity?

    @Upsert
    suspend fun upsert(app: TrackedAppEntity)

    @Query("DELETE FROM tracked_apps WHERE packageName NOT IN (:activePkgs)")
    suspend fun removeStale(activePkgs: List<String>)

    @Query("UPDATE tracked_apps SET currentVersion = :ver, lastCheckedAt = :ts WHERE packageName = :pkg")
    suspend fun updateCurrentVersion(pkg: String, ver: String, ts: Long)

    @Query("SELECT * FROM tracked_apps ORDER BY appName ASC")
    suspend fun getAll(): List<TrackedAppEntity>
}
