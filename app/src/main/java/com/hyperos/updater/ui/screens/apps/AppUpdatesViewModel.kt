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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class AppUpdatesUiState(
    val isLoading: Boolean = false,
    val updates: List<AppUpdate> = emptyList(),
    val downloading: Map<String, Int> = emptyMap(),
    val error: String? = null
)

@HiltViewModel
class AppUpdatesViewModel @Inject constructor(
    private val app: Application,
    private val checkSystemAppUpdatesUseCase: CheckSystemAppUpdatesUseCase,
    private val checkThirdPartyAppUpdatesUseCase: CheckThirdPartyAppUpdatesUseCase,
    private val downloadUpdateUseCase: DownloadUpdateUseCase,
    private val installApkUseCase: InstallApkUseCase,
    private val apkPureService: ApkPureService,
    private val apkComboService: ApkComboService,
    val downloadManager: DownloadManager
) : ViewModel() {

    private val _state = MutableStateFlow(AppUpdatesUiState())
    val state: StateFlow<AppUpdatesUiState> = _state

    private val downloadsDir = File(app.filesDir, "downloads").also { it.mkdirs() }
    private var checkJob: kotlinx.coroutines.Job? = null

    fun checkAllApps() {
        checkJob?.cancel()
        _state.value = AppUpdatesUiState(isLoading = true)
        checkJob = viewModelScope.launch {
            val allUpdates = mutableListOf<AppUpdate>()
            val systemJob = launch {
                checkSystemAppUpdatesUseCase().collect { update ->
                    allUpdates.add(update)
                    _state.value = _state.value.copy(updates = sorted(allUpdates))
                }
            }
            val thirdPartyJob = launch {
                checkThirdPartyAppUpdatesUseCase().collect { update ->
                    allUpdates.add(update)
                    _state.value = _state.value.copy(updates = sorted(allUpdates))
                }
            }
            systemJob.join()
            thirdPartyJob.join()
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    fun openSourcePage(update: AppUpdate) {
        val url = update.downloadUrl ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "No browser available")
        }
    }

    fun downloadAndInstall(update: AppUpdate) {
        viewModelScope.launch {
            try {
                // Resolve actual APK download URL
                val apkUrl = when (update.updateSource) {
                    UpdateSource.APKPURE -> "https://d.apkpure.com/b/APK/${update.packageName}?version=latest"
                    UpdateSource.APKCOMBO -> {
                        // APKCombo needs JS — open in browser
                        val pageUrl = update.downloadUrl ?: "https://apkcombo.com/search/${update.packageName}"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$pageUrl/download/apk"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        app.startActivity(intent)
                        return@launch
                    }
                    else -> update.downloadUrl
                        ?: run {
                            _state.value = _state.value.copy(error = "No download URL")
                            return@launch
                        }
                }

                Log.d("AppVM", "Resolved APK URL: $apkUrl")

                val filename = "${update.packageName}_${update.latestVersion}.apk"
                downloadUpdateUseCase.download(apkUrl, filename, null).collect { progress ->
                    val map = _state.value.downloading.toMutableMap()
                    map[update.packageName] = progress.progress
                    _state.value = _state.value.copy(downloading = map)
                }

                val file = File(downloadsDir, filename)
                if (!file.exists() || file.length() == 0L) {
                    _state.value = _state.value.copy(error = "Download failed: empty file")
                    return@launch
                }

                Log.d("AppVM", "Downloaded ${file.length()} bytes, installing...")
                installApkUseCase(file, update.packageName, update.appType == AppType.SYSTEM)

                val map = _state.value.downloading.toMutableMap()
                map.remove(update.packageName)
                _state.value = _state.value.copy(downloading = map)
            } catch (e: Exception) {
                Log.e("AppVM", "Install failed", e)
                _state.value = _state.value.copy(error = e.message ?: "Install failed")
            }
        }
    }

    private fun sorted(list: List<AppUpdate>): List<AppUpdate> =
        list.sortedWith(
            compareByDescending<AppUpdate> { it.updateSource != UpdateSource.UNTRACKED && it.currentVersion != it.latestVersion }
                .thenBy { it.appName.lowercase() }
        )
}
