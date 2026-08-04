package com.hyperos.updater.domain.installer

import android.content.Context
import android.content.Intent
import android.util.Log
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
        return if (openInstallIntent(apkFile)) InstallResult.Success
        else InstallResult.Failure("Package installer failed")
    }

    /**
     * Opens the system's default installer for [file].
     *
     * MIME type is auto-detected:
     * - `.apk` → `application/vnd.android.package-archive`
     * - `.xapk` / `.apkm` / `.apks` → `application/octet-stream`
     *   (SAI-style installers register for these extensions; octet-stream
     *    lets the system show all capable handlers.)
     *
     * @return true if the intent was launched successfully.
     */
    fun openInstallIntent(file: File): Boolean {
        return try {
            val ext = file.name.substringAfterLast('.', "").lowercase()
            val mime = when (ext) {
                "apk" -> "application/vnd.android.package-archive"
                "xapk", "apkm", "apks" -> "application/octet-stream"
                else -> "application/vnd.android.package-archive"
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            context.startActivity(intent)
            Log.i("PackageManagerInstaller", "System installer opened for ${file.name} (mime=$mime)")
            true
        } catch (e: Exception) {
            Log.e("PackageManagerInstaller", "Failed to open installer for ${file.name}: ${e.message}")
            false
        }
    }
}
