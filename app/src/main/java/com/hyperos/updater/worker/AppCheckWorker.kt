package com.hyperos.updater.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hyperos.updater.domain.DownloadManager
import com.hyperos.updater.domain.model.AppUpdate
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
    private val downloadManager: DownloadManager,
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

            // Auto-update ON: download each update in background, then notify.
            // Silent install is impossible without root/session — the user taps
            // the notification to install via the system installer.
            val successNames = mutableListOf<String>()
            val failDetails = mutableListOf<String>()
            val skippedNames = mutableListOf<String>()

            val downloadsDir = downloadManager.downloadsDirectory

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

                    val fileName = buildWorkerFileName(resolvedUrl, update.appName, directSource.version)
                    val key = "worker_${update.packageName}"

                    // Download to the public downloads directory (same as DownloadManager)
                    Log.i(TAG, "Downloading ${update.appName} from ${directSource.source}")
                    downloadUseCase.download(resolvedUrl, fileName, null, downloadsDir).collect {
                        // progress — just consume the flow
                    }

                    val apkFile = File(downloadsDir, fileName)
                    if (!apkFile.exists() || apkFile.length() == 0L) {
                        failDetails.add("${update.appName}: download failed (empty file)")
                        continue
                    }

                    // Register with DownloadManager so it appears in the Downloads tab
                    downloadManager.registerCompletedDownload(key, update.appName, fileName)

                    Log.i(TAG, "Downloaded ${update.appName} (${update.latestVersion})")
                    successNames.add("${update.appName} (${update.latestVersion})")
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-update download failed for ${update.appName}", e)
                    failDetails.add("${update.appName}: ${e.message}")
                }
            }

            notificationHelper.showDownloadsReady(
                successCount = successNames.size,
                failCount = failDetails.size,
                skippedCount = skippedNames.size
            )

            Log.i(TAG, "Auto-update complete: ${successNames.size} downloaded, ${failDetails.size} fail, ${skippedNames.size} skipped")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto-update worker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AppCheckWorker"
        /** Sources that provide direct APK download URLs (no WebView/intermediary page needed). */
        private val DIRECT_DOWNLOAD_SOURCES = setOf(UpdateSource.APTOIDE, UpdateSource.GITHUB, UpdateSource.FDROID, UpdateSource.MEMEOS, UpdateSource.TENCENT)
    }
}

/** Builds a meaningful filename for auto-update downloads, mirroring DownloadManager's naming. */
private fun buildWorkerFileName(url: String, appName: String, version: String): String {
    val urlPath = url.substringBefore('?').substringAfterLast('/')
    val ext = urlPath.substringAfterLast('.', "apk").lowercase()
    val base = urlPath.substringBeforeLast('.')
    // Use the URL's filename when meaningful; otherwise build from app+version
    val meaningfulBase = base.isNotBlank() && base.length > 3 &&
        !base.matches(Regex("^[0-9]+$")) && base.length < 40
    val name = if (meaningfulBase) base else appName.replace(" ", "-")
    return "$name-v$version.$ext"
}
