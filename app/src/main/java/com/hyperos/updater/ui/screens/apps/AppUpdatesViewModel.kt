package com.hyperos.updater.ui.screens.apps

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyperos.updater.data.local.dao.TrackedAppDao
import com.hyperos.updater.data.local.entity.TrackedAppEntity
import com.hyperos.updater.domain.DownloadManager
import com.hyperos.updater.domain.model.AppUpdate
import com.hyperos.updater.domain.model.AppType
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.domain.usecase.CheckSystemAppUpdatesUseCase
import com.hyperos.updater.domain.usecase.CheckThirdPartyAppUpdatesUseCase
import com.hyperos.updater.ui.components.DownloadStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppUpdatesViewModel @Inject constructor(
    private val app: Application,
    private val trackedAppDao: TrackedAppDao,
    private val checkSystemAppUpdatesUseCase: CheckSystemAppUpdatesUseCase,
    private val checkThirdPartyAppUpdatesUseCase: CheckThirdPartyAppUpdatesUseCase,
    val downloadManager: DownloadManager
) : ViewModel() {

    private val _cache = MutableStateFlow<Map<String, AppUpdate>>(emptyMap())
    val cache: StateFlow<Map<String, AppUpdate>> = _cache.asStateFlow()
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _pendingDlKey = MutableStateFlow<String?>(null)
    val pendingDownloadKey: StateFlow<String?> = _pendingDlKey.asStateFlow()

    private var checkJob: kotlinx.coroutines.Job? = null

    init {
        // Restore cache from Room on startup
        viewModelScope.launch {
            val saved = trackedAppDao.getAll()
            if (saved.isNotEmpty()) {
                _cache.value = saved.map { entity ->
                    entity.packageName to AppUpdate(
                        packageName = entity.packageName,
                        appName = entity.appName,
                        currentVersion = entity.currentVersion,
                        latestVersion = entity.latestVersion ?: entity.currentVersion,
                        latestVersionCode = entity.latestVersionCode ?: 0L,
                        fileSize = null,
                        downloadUrl = null,
                        changelog = null,
                        publishedDate = null,
                        updateSource = try { UpdateSource.valueOf(entity.updateSource) } catch (_: Exception) { UpdateSource.UNTRACKED },
                        appType = try { AppType.valueOf(entity.appType) } catch (_: Exception) { AppType.THIRD_PARTY }
                    )
                }.toMap()
            }
        }
        // Watch for completed downloads to update the cache
        viewModelScope.launch {
            downloadManager.downloads.collect { downloads ->
                downloads.forEach { (key, dl) ->
                    if (dl.progress.status == DownloadStatus.COMPLETED) {
                        // Update installed app's currentVersion to latestVersion
                        val current = _cache.value
                        val update = current.values.find {
                            (it.updateSource.name + it.appName) == key
                        } ?: return@forEach
                        val updated = update.copy(currentVersion = update.latestVersion)
                        _cache.value = _cache.value + (updated.packageName to updated)
                        // Persist to Room
                        viewModelScope.launch {
                            val entity = trackedAppDao.getByPackage(updated.packageName)
                            if (entity != null) {
                                trackedAppDao.updateCurrentVersion(
                                    updated.packageName, updated.latestVersion, System.currentTimeMillis()
                                )
                            }
                        }
                        downloadManager.dismissDownload(key)
                    }
                }
            }
        }
    }

    fun checkAllApps() {
        checkJob?.cancel()
        _isScanning.value = true
        _error.value = null
        checkJob = viewModelScope.launch {
            val scannedPackages = mutableSetOf<String>()
            val systemJob = launch {
                checkSystemAppUpdatesUseCase().collect { update ->
                    scannedPackages.add(update.packageName)
                    _cache.value = _cache.value + (update.packageName to update)
                }
            }
            val thirdPartyJob = launch {
                checkThirdPartyAppUpdatesUseCase().collect { update ->
                    scannedPackages.add(update.packageName)
                    _cache.value = _cache.value + (update.packageName to update)
                }
            }
            systemJob.join()
            thirdPartyJob.join()
            _cache.value = _cache.value.filterKeys { it in scannedPackages }

            // Persist entire cache to Room
            val now = System.currentTimeMillis()
            _cache.value.values.forEach { update ->
                trackedAppDao.upsert(
                    TrackedAppEntity(
                        packageName = update.packageName,
                        appName = update.appName,
                        currentVersion = update.currentVersion,
                        latestVersion = update.latestVersion,
                        latestVersionCode = update.latestVersionCode,
                        appType = update.appType.name,
                        updateSource = update.updateSource.name,
                        apkMirrorSlug = null,
                        lastCheckedAt = now
                    )
                )
            }
            _isScanning.value = false
        }
    }

    fun openSourcePage(update: AppUpdate) {
        val url = update.downloadUrl ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        } catch (e: Exception) { _error.value = "No browser available" }
    }

    fun getDownloadPageUrl(update: AppUpdate): String {
        return when (update.updateSource) {
            UpdateSource.APKPURE -> "https://apkpure.com/apk/${update.packageName}"
            UpdateSource.APKCOMBO -> "https://apkcombo.com/search/${update.packageName}/download/apk"
            UpdateSource.APKMIRROR -> {
                val pageUrl = update.downloadUrl ?: return "https://www.apkmirror.com/?s=${update.packageName}&post_type=app_release"
                val base = pageUrl.trimEnd('/')
                val slug = base.split("/").last { it.isNotBlank() }
                "$base/${slug.replace("-release", "-android-apk-download")}/"
            }
            else -> "https://apkpure.com/apk/${update.packageName}"
        }
    }

    fun onDownloadUrlCaptured(url: String) {
        val key = _pendingDlKey.value ?: return
        val filename = url.split("/").lastOrNull()?.substringBefore("?")
            ?.takeIf { it.isNotBlank() } ?: "downloaded.apk"
        downloadManager.startDownload(url, filename, key, key.removePrefix("APKMIRROR").removePrefix("APKPURE").removePrefix("APKCOMBO"))
        _pendingDlKey.value = null
    }

    fun setPendingDownloadKey(key: String) {
        _pendingDlKey.value = key
    }
}
