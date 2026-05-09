package com.hyperos.updater.domain.installer

import java.io.File

interface ApkInstaller {
    suspend fun install(apkFile: File, packageName: String, isSystemApp: Boolean): InstallResult
}
