package com.hyperos.updater.ui.screens.ota

import android.app.Application
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyperos.updater.data.remote.XiaomiEuService
import com.hyperos.updater.domain.model.OtaUpdate
import com.hyperos.updater.domain.model.UpdateState
import com.hyperos.updater.domain.usecase.DownloadUpdateUseCase
import com.hyperos.updater.domain.usecase.GetDeviceInfoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class OtaViewModel @Inject constructor(
    private val app: Application,
    private val downloadUpdateUseCase: DownloadUpdateUseCase,
    private val getDeviceInfoUseCase: GetDeviceInfoUseCase,
    private val xiaomiEuService: XiaomiEuService
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val _currentVersion = MutableStateFlow("")
    val currentVersion: StateFlow<String> = _currentVersion

    private val _hasChecked = MutableStateFlow(false)
    val hasChecked: StateFlow<Boolean> = _hasChecked

    private val downloadsDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "HyperOSUpdater"
    ).also { it.mkdirs() }

    @Volatile var lastAvailableUpdate: OtaUpdate? = null
        private set

    init {
        loadDeviceInfo()
    }

    private fun loadDeviceInfo() {
        viewModelScope.launch {
            val device = getDeviceInfoUseCase()
            _currentVersion.value = device.miuiVersion
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _state.value = UpdateState.Checking
            try {
                val device = getDeviceInfoUseCase()
                val rom = xiaomiEuService.checkLatestRom(device.codename)
                _hasChecked.value = true

                if (rom == null) {
                    _state.value = UpdateState.Error(
                        "No xiaomi.eu ROM found for ${device.marketingName} (${device.codename})"
                    )
                    return@launch
                }

                // Extract numeric parts from installed version (handle "OS3.0.9.0" and "OS3.0.9.0.WPBCNXM")
                val installedParts = extractNumericParts(device.miuiVersion)
                val romParts = extractNumericParts(rom.version)

                val isUpdate = isNumericNewer(installedParts, romParts)

                val otaUpdate = OtaUpdate(
                    version = rom.version,
                    androidVersion = device.androidVersion,
                    branch = "xiaomi.eu",
                    fileSize = rom.sizeBytes,
                    md5 = rom.md5,
                    changelog = null,
                    downloadUrl = rom.downloadUrl,
                    filename = rom.fileName,
                    publishedDate = rom.publishedDate
                )

                lastAvailableUpdate = otaUpdate
                _state.value = if (isUpdate) UpdateState.Available(otaUpdate) else UpdateState.Idle
            } catch (e: Exception) {
                _state.value = UpdateState.Error(e.message ?: "Failed to check updates")
            }
        }
    }

    fun downloadUpdate(
        url: String,
        filename: String,
        md5: String?,
        headers: Map<String, String> = emptyMap()
    ) {
        viewModelScope.launch {
            try {
                downloadUpdateUseCase.download(url, filename, md5, downloadsDir, headers)
                    .collect { progress ->
                        _state.value = UpdateState.Downloading(
                            progress = progress.progress,
                            bytesDownloaded = progress.bytesDownloaded,
                            totalBytes = progress.totalBytes
                        )
                    }
                val file = File(downloadsDir, filename)
                _state.value = UpdateState.ReadyToInstall(file.absolutePath, filename)
            } catch (e: Exception) {
                _state.value = UpdateState.Error(e.message ?: "Download failed")
            }
        }
    }

    fun retryDownload() {
        val update = lastAvailableUpdate ?: return
        val url = update.downloadUrl ?: return
        val filename = update.filename ?: "update.zip"
        downloadUpdate(url, filename, update.md5)
    }

    companion object {
        /**
         * Extracts up to 4 leading numeric components from a HyperOS/MIUI version string.
         * Handles "OS3.0.9.0", "OS3.0.9.0.WPBCNXM", "3.0.9.0", etc.
         * Padded to 4 components with zeros.
         */
        fun extractNumericParts(version: String): List<Int> {
            val cleaned = version.removePrefix("OS").removePrefix("os")
            val parts = cleaned.split(".").mapNotNull { it.toIntOrNull() }
            return (parts + List(4) { 0 }).take(4)
        }

        /**
         * Lexicographic comparison of two 4-component numeric version lists.
         * Returns true if [candidate] is strictly newer than [installed].
         */
        fun isNumericNewer(installed: List<Int>, candidate: List<Int>): Boolean {
            for (i in 0 until 4) {
                val c = candidate.getOrElse(i) { 0 }
                val e = installed.getOrElse(i) { 0 }
                if (c > e) return true
                if (c < e) return false
            }
            return false
        }
    }
}
