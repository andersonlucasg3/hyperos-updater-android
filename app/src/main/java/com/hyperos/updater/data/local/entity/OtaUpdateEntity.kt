package com.hyperos.updater.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ota_updates")
data class OtaUpdateEntity(
    @PrimaryKey val id: Long = 1,
    val version: String,
    val androidVersion: String,
    val branch: String,
    val fileSize: Long,
    val md5: String?,
    val changelog: String?,
    val filename: String?,
    val checkedAt: Long,
    val installedVersion: String?
)
