package com.hyperos.updater.domain.model

data class OtaUpdate(
    val version: String,
    val androidVersion: String,
    val branch: String,
    val fileSize: Long,
    val md5: String?,
    val changelog: String?,
    val downloadUrl: String?,
    val filename: String?,
    val publishedDate: String?
)
