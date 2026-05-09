package com.hyperos.updater.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracked_apps",
    indices = [Index(value = ["packageName"], unique = true)]
)
data class TrackedAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val currentVersion: String,
    val latestVersion: String?,
    val latestVersionCode: Long?,
    val appType: String,
    val updateSource: String,
    val apkMirrorSlug: String?,
    val lastCheckedAt: Long
)
