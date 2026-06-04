package com.hyperos.updater.ui.screens.apps

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
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

    // Compose-optimized list: element-level change tracking, O(1) element updates
    val appList = mutableStateListOf<AppUpdate>()
    // Package → index for O(1) lookup
    private val pkgIndex = mutableMapOf<String, Int>()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _pendingDlKey = MutableStateFlow<String?>(null)
    val pendingDownloadKey: StateFlow<String?> = _pendingDlKey.asStateFlow()

    private var checkJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            val saved = trackedAppDao.getAll()
            if (saved.isNotEmpty()) {
                saved.forEach { entity ->
                    val update = entityToAppUpdate(entity)
                    pkgIndex[update.packageName] = appList.size
                    appList.add(update)
                }
            }
        }
        viewModelScope.launch {
            downloadManager.downloads.collect { downloads ->
                downloads.forEach { (key, dl) ->
                    if (dl.progress.status == DownloadStatus.COMPLETED) {
                        val idx = pkgIndex.values.firstOrNull { i ->
                            val u = appList.getOrNull(i); u != null && (u.updateSource.name + u.appName) == key
                        } ?: return@forEach
                        val update = appList[idx]
                        appList[idx] = update.copy(currentVersion = update.latestVersion)
                        viewModelScope.launch {
                            trackedAppDao.updateCurrentVersion(update.packageName, update.latestVersion, System.currentTimeMillis())
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
            val scanned = mutableSetOf<String>()
            val systemJob = launch {
                checkSystemAppUpdatesUseCase().collect { update -> upsert(update); scanned.add(update.packageName) }
            }
            val thirdPartyJob = launch {
                checkThirdPartyAppUpdatesUseCase().collect { update -> upsert(update); scanned.add(update.packageName) }
            }
            systemJob.join()
            thirdPartyJob.join()

            // Remove stale entries
            val toRemove = appList.mapIndexedNotNull { i, u -> if (u.packageName !in scanned) i else null }.sortedDescending()
            toRemove.forEach { i ->
                val pkg = appList[i].packageName
                pkgIndex.remove(pkg)
                appList.removeAt(i)
            }
            // Fix indices after removals
            pkgIndex.clear()
            appList.forEachIndexed { i, u -> pkgIndex[u.packageName] = i }

            // Persist
            val now = System.currentTimeMillis()
            appList.forEach { update ->
                trackedAppDao.upsert(TrackedAppEntity(
                    packageName = update.packageName, appName = update.appName,
                    currentVersion = update.currentVersion, latestVersion = update.latestVersion,
                    latestVersionCode = update.latestVersionCode,
                    appType = update.appType.name, updateSource = update.updateSource.name,
                    apkMirrorSlug = null, lastCheckedAt = now
                ))
            }
            _isScanning.value = false
        }
    }

    private fun upsert(update: AppUpdate) {
        val idx = pkgIndex[update.packageName]
        if (idx != null && idx < appList.size && appList[idx].packageName == update.packageName) {
            // Update in place — Compose tracks element-level changes
            appList[idx] = update
        } else {
            // New entry
            pkgIndex[update.packageName] = appList.size
            appList.add(update)
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

    private fun entityToAppUpdate(e: TrackedAppEntity) = AppUpdate(
        packageName = e.packageName, appName = e.appName,
        currentVersion = e.currentVersion, latestVersion = e.latestVersion ?: e.currentVersion,
        latestVersionCode = e.latestVersionCode ?: 0L,
        fileSize = null, downloadUrl = null, changelog = null, publishedDate = null,
        updateSource = try { UpdateSource.valueOf(e.updateSource) } catch (_: Exception) { UpdateSource.UNTRACKED },
        appType = try { AppType.valueOf(e.appType) } catch (_: Exception) { AppType.THIRD_PARTY }
    )
}
