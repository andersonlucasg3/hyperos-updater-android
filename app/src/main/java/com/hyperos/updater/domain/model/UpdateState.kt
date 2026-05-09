package com.hyperos.updater.domain.model

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class Available(val update: OtaUpdate) : UpdateState()
    data class Downloading(val progress: Int, val bytesDownloaded: Long, val totalBytes: Long) : UpdateState()
    data class ReadyToInstall(val filePath: String, val version: String) : UpdateState()
    data class Installing(val version: String) : UpdateState()
    data object Installed : UpdateState()
    data class Error(val message: String) : UpdateState()
}
