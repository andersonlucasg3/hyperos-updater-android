package com.hyperos.updater.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "update_history",
    indices = [Index(value = ["packageName"])]
)
data class UpdateHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val versionFrom: String,
    val versionTo: String,
    val installedAt: Long,
    val installMethod: String
)
