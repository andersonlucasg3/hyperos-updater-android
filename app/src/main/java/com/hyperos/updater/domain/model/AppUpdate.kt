package com.hyperos.updater.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class AppUpdate(
    val packageName: String,
    val appName: String,
    val currentVersion: String,
    val latestVersion: String,
    val latestVersionCode: Long,
    val fileSize: Long?,
    val downloadUrl: String?,
    val changelog: String?,
    val publishedDate: String?,
    val updateSource: UpdateSource,
    val appType: AppType
)
