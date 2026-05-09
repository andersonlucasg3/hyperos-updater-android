package com.hyperos.updater.domain.model

data class DeviceInfo(
    val codename: String,
    val marketingName: String,
    val miuiVersion: String,
    val androidVersion: String,
    val androidSdk: Int,
    val region: String,
    val isGlobal: Boolean
)
