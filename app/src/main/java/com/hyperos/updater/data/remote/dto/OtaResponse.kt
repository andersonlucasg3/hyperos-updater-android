package com.hyperos.updater.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OtaResponse(
    @Json(name = "version") val version: String?,
    @Json(name = "android") val androidVersion: String?,
    @Json(name = "branch") val branch: String?,
    @Json(name = "description") val changelog: String?,
    @Json(name = "filesize") val filesize: String?,
    @Json(name = "md5") val md5: String?,
    @Json(name = "filename") val filename: String?,
    @Json(name = "ultimateota") val ultimateota: String?,
    @Json(name = "superota") val superota: String?,
    @Json(name = "cdnorg") val cdnorg: String?,
    @Json(name = "aliyuncs") val aliyuncs: String?
)
