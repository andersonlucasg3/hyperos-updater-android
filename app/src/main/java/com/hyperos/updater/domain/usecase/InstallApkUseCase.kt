package com.hyperos.updater.domain.usecase

import com.hyperos.updater.domain.installer.ApkInstaller
import com.hyperos.updater.domain.installer.InstallResult
import java.io.File
import javax.inject.Inject
import javax.inject.Named

class InstallApkUseCase @Inject constructor(
    @Named("shizuku") private val shizukuInstaller: ApkInstaller,
    @Named("fallback") private val fallbackInstaller: ApkInstaller
) {
    suspend operator fun invoke(apkFile: File, packageName: String, isSystemApp: Boolean): InstallResult {
        val result = shizukuInstaller.install(apkFile, packageName, isSystemApp)
        return if (result is InstallResult.ShizukuNotAvailable) {
            fallbackInstaller.install(apkFile, packageName, isSystemApp)
        } else {
            result
        }
    }
}
