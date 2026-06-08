package com.hyperos.updater.domain.model

enum class OtaSource { XIAOMI_API, MEMEOS }

data class OtaSourceInfo(
    val source: OtaSource,
    val version: String,
    val downloadUrl: String?
)

data class OtaUpdate(
    val version: String,
    val androidVersion: String,
    val branch: String,
    val fileSize: Long,
    val md5: String?,
    val changelog: String?,
    val downloadUrl: String?,
    val filename: String?,
    val publishedDate: String?,
    val source: OtaSource = OtaSource.XIAOMI_API,
    val otaSources: List<OtaSourceInfo> = emptyList()
)
