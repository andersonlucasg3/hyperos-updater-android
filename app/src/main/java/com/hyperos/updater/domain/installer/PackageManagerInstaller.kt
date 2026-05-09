package com.hyperos.updater.domain.installer

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PackageManagerInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) : ApkInstaller {

    override suspend fun install(apkFile: File, packageName: String, isSystemApp: Boolean): InstallResult {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            InstallResult.Success
        } catch (e: Exception) {
            InstallResult.Failure(e.message ?: "Package installer failed")
        }
    }
}
