package com.hyperos.updater.ui.screens.apps

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyperos.updater.domain.DownloadManager
import com.hyperos.updater.domain.model.AppUpdate
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.domain.usecase.CheckSystemAppUpdatesUseCase
import com.hyperos.updater.domain.usecase.CheckThirdPartyAppUpdatesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppUpdatesUiState(
    val isScanning: Boolean = false,
    val updates: List<AppUpdate> = emptyList(),
    val downloading: Map<String, Int> = emptyMap(),
    val error: String? = null
)

@HiltViewModel
class AppUpdatesViewModel @Inject constructor(
    private val app: Application,
    private val checkSystemAppUpdatesUseCase: CheckSystemAppUpdatesUseCase,
    private val checkThirdPartyAppUpdatesUseCase: CheckThirdPartyAppUpdatesUseCase,
    val downloadManager: DownloadManager
) : ViewModel() {

    // Persistent cache keyed by packageName — survives across scans
    private val _cache = MutableStateFlow<Map<String, AppUpdate>>(emptyMap())
    private val _isScanning = MutableStateFlow(false)
    private val _downloading = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _error = MutableStateFlow<String?>(null)
    private val _pendingDlKey = MutableStateFlow<String?>(null)

    val state: StateFlow<AppUpdatesUiState> = combine(
        _cache, _isScanning, _downloading, _error
    ) { cache, scanning, downloading, error ->
        AppUpdatesUiState(
            isScanning = scanning,
            updates = sorted(cache.values.toList()),
            downloading = downloading,
            error = error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppUpdatesUiState())

    val pendingDownloadKey: StateFlow<String?> = _pendingDlKey

    private var checkJob: kotlinx.coroutines.Job? = null

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
            _isScanning.value = false
        }
    }

    fun openSourcePage(update: AppUpdate) {
        val url = update.downloadUrl ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        } catch (e: Exception) {
            _error.value = "No browser available"
        }
    }

    /** Returns the URL that DownloadActivity should load to capture the download link */
    fun getDownloadPageUrl(update: AppUpdate): String {
        return when (update.updateSource) {
            UpdateSource.APKPURE -> "https://apkpure.com/apk/${update.packageName}"
            UpdateSource.APKCOMBO -> "https://apkcombo.com/search/${update.packageName}/download/apk"
            UpdateSource.APKMIRROR -> {
                // APKMirror needs specific download page URL
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

    private fun sorted(list: List<AppUpdate>): List<AppUpdate> =
        list.sortedWith(
            compareByDescending<AppUpdate> { it.updateSource != UpdateSource.UNTRACKED && it.currentVersion != it.latestVersion }
                .thenBy { it.appName.lowercase() }
        )
}
