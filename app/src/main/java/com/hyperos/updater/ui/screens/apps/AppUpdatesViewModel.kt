package com.hyperos.updater.ui.screens.apps

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyperos.updater.data.remote.ApkComboService
import com.hyperos.updater.data.remote.ApkPureService
import com.hyperos.updater.domain.DownloadManager
import com.hyperos.updater.domain.model.AppType
import com.hyperos.updater.domain.model.AppUpdate
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.domain.usecase.CheckSystemAppUpdatesUseCase
import com.hyperos.updater.domain.usecase.CheckThirdPartyAppUpdatesUseCase
import com.hyperos.updater.domain.usecase.DownloadUpdateUseCase
import com.hyperos.updater.domain.usecase.InstallApkUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
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

    private val downloadsDir = File(app.filesDir, "downloads").also { it.mkdirs() }
    private var checkJob: kotlinx.coroutines.Job? = null

    fun checkAllApps() {
        checkJob?.cancel()
        // Show existing cache immediately — do NOT clear
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

            // Remove cached entries for apps no longer installed
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

    fun downloadAndInstall(update: AppUpdate) {
        val key = update.updateSource.name + update.appName
        val url = when (update.updateSource) {
            UpdateSource.APKPURE -> "https://d.apkpure.com/b/APK/${update.packageName}?version=latest"
            UpdateSource.APKCOMBO -> {
                val pageUrl = update.downloadUrl ?: "https://apkcombo.com/search/${update.packageName}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$pageUrl/download/apk"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
                return
            }
            else -> {
                val pageUrl = "https://apkpure.com/apk/${update.packageName}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
                return
            }
        }
        val filename = "${update.packageName}_${update.latestVersion}.apk"
        downloadManager.startDownload(url, filename, key, update.appName)
    }

    private fun sorted(list: List<AppUpdate>): List<AppUpdate> =
        list.sortedWith(
            compareByDescending<AppUpdate> { it.updateSource != UpdateSource.UNTRACKED && it.currentVersion != it.latestVersion }
                .thenBy { it.appName.lowercase() }
        )
}
