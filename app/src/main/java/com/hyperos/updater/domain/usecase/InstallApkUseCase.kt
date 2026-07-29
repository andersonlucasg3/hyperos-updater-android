package com.hyperos.updater.domain.usecase

import com.hyperos.updater.domain.installer.ApkInstaller
import com.hyperos.updater.domain.installer.InstallResult
import java.io.File
import javax.inject.Inject
import javax.inject.Named

class InstallApkUseCase @Inject constructor(
    @Named("root") private val rootInstaller: ApkInstaller,
    @Named("fallback") private val fallbackInstaller: ApkInstaller
) {
    suspend operator fun invoke(apkFile: File, packageName: String, isSystemApp: Boolean): InstallResult {
        val result = rootInstaller.install(apkFile, packageName, isSystemApp)
        return if (result is InstallResult.RootNotAvailable) {
            fallbackInstaller.install(apkFile, packageName, isSystemApp)
        } else {
            result
        }
    }
}
