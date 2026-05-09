package com.hyperos.updater.domain.installer

sealed class InstallResult {
    data object Success : InstallResult()
    data class Failure(val reason: String) : InstallResult()
    data object ShizukuNotAvailable : InstallResult()
}
