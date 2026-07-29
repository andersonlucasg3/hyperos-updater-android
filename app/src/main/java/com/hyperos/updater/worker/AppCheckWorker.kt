package com.hyperos.updater.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hyperos.updater.domain.installer.InstallResult
import com.hyperos.updater.domain.installer.RootApkInstaller
import com.hyperos.updater.domain.model.AppUpdate
import com.hyperos.updater.domain.model.AppType
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.domain.repository.PreferencesRepository
import com.hyperos.updater.domain.usecase.CheckSystemAppUpdatesUseCase
import com.hyperos.updater.domain.usecase.CheckThirdPartyAppUpdatesUseCase
import com.hyperos.updater.domain.usecase.DownloadUpdateUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File

@HiltWorker
class AppCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checkSystemAppUpdatesUseCase: CheckSystemAppUpdatesUseCase,
    private val checkThirdPartyAppUpdatesUseCase: CheckThirdPartyAppUpdatesUseCase,
    private val notificationHelper: NotificationHelper,
    private val preferencesRepository: PreferencesRepository,
    private val downloadUseCase: DownloadUpdateUseCase,
    private val rootInstaller: RootApkInstaller,
    private val memeOsService: com.hyperos.updater.data.remote.MemeOsService
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val autoUpdateEnabled = preferencesRepository.autoUpdateEnabled.first()
            val blacklist = preferencesRepository.blacklistedPackages.first()
            val skippedVersions = preferencesRepository.skippedVersions.first()

            // Collect all updates
            val updates = mutableListOf<AppUpdate>()
            checkSystemAppUpdatesUseCase().collect { update ->
                val skipKey = "${update.packageName}|${update.latestVersion}"
                if (update.currentVersion != update.latestVersion && update.packageName !in blacklist && skipKey !in skippedVersions)
                    updates.add(update)
            }
            checkThirdPartyAppUpdatesUseCase().collect { update ->
                val skipKey = "${update.packageName}|${update.latestVersion}"
                if (update.currentVersion != update.latestVersion && update.packageName !in blacklist && skipKey !in skippedVersions)
                    updates.add(update)
            }

            if (!autoUpdateEnabled) {
                if (updates.isNotEmpty()) {
                    notificationHelper.showAppUpdatesAvailable(updates.size)
                }
                return Result.success()
            }

            // Auto-update mode: download and install each update silently
            val successNames = mutableListOf<String>()
            val failDetails = mutableListOf<String>()
            val skippedNames = mutableListOf<String>()

            val tempDir = File(applicationContext.cacheDir, "auto_update")
            tempDir.mkdirs()

            for (update in updates) {
                // Find best source with a direct download URL
                val directSource = update.sourceVersions.firstOrNull { sv ->
                    sv.downloadUrl != null && sv.source in DIRECT_DOWNLOAD_SOURCES
                }

                if (directSource == null) {
                    Log.i(TAG, "No direct URL for ${update.appName} — skipping")
                    skippedNames.add(update.appName)
                    continue
                }

                try {
                    // MEMEOS: the "download URL" is a version page — resolve to a signed direct URL first
                    val resolvedUrl = if (directSource.source == UpdateSource.MEMEOS) {
                        memeOsService.resolveDirectDownloadUrl(directSource.downloadUrl!!)
                    } else {
                        directSource.downloadUrl
                    }
                    if (resolvedUrl == null) {
                        Log.i(TAG, "MEMEOS resolution failed for ${update.appName} — skipping")
                        skippedNames.add(update.appName)
                        continue
                    }

                    val fileName = "${update.packageName}_${System.currentTimeMillis()}.apk"
                    val apkFile = File(tempDir, fileName)

                    // Download
                    Log.i(TAG, "Downloading ${update.appName} from ${directSource.source}")
                    downloadUseCase.download(resolvedUrl, fileName, null, tempDir).collect {
                        // progress — just consume the flow
                    }

                    if (!apkFile.exists() || apkFile.length() == 0L) {
                        failDetails.add("${update.appName}: download failed (empty file)")
                        continue
                    }

                    // Install silently via root
                    val isSystem = update.appType == AppType.SYSTEM
                    val result = installSilently(apkFile, update.packageName, isSystem)

                    // Clean up APK
                    apkFile.delete()

                    when (result) {
                        is InstallResult.Success -> {
                            Log.i(TAG, "Installed ${update.appName}")
                            successNames.add("${update.appName} (${update.latestVersion})")
                        }
                        is InstallResult.Failure -> {
                            failDetails.add("${update.appName}: ${result.reason}")
                        }
                        is InstallResult.RootNotAvailable -> {
                            failDetails.add("${update.appName}: root não disponível")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-update failed for ${update.appName}", e)
                    failDetails.add("${update.appName}: ${e.message}")
                }
            }

            // Clean up temp dir
            tempDir.deleteRecursively()

            // Build details string
            val details = buildString {
                if (successNames.isNotEmpty()) {
                    append("Installed: ")
                    appendLine(successNames.joinToString(", "))
                }
                if (failDetails.isNotEmpty()) {
                    append("Failed: ")
                    appendLine(failDetails.joinToString(", "))
                }
                if (skippedNames.isNotEmpty()) {
                    append("Skipped (no direct download): ")
                    appendLine(skippedNames.joinToString(", "))
                }
            }.trimEnd()

            notificationHelper.showAutoUpdateResults(
                successCount = successNames.size,
                failCount = failDetails.size,
                skippedCount = skippedNames.size,
                details = details
            )

            Log.i(TAG, "Auto-update complete: ${successNames.size} ok, ${failDetails.size} fail, ${skippedNames.size} skipped")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto-update worker failed", e)
            Result.retry()
        }
    }

    private suspend fun installSilently(apkFile: File, packageName: String, isSystemApp: Boolean): InstallResult {
        // Root only — no fallback in background (Session/Intent require user interaction)
        return rootInstaller.install(apkFile, packageName, isSystemApp)
    }

    companion object {
        private const val TAG = "AppCheckWorker"
        /** Sources that provide direct APK download URLs (no WebView/intermediary page needed). */
        private val DIRECT_DOWNLOAD_SOURCES = setOf(UpdateSource.APTOIDE, UpdateSource.GITHUB, UpdateSource.FDROID, UpdateSource.MEMEOS, UpdateSource.TENCENT)
    }
}
