package com.hyperos.updater.ui.screens.ota

import android.app.Application
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyperos.updater.domain.model.OtaUpdate
import com.hyperos.updater.domain.model.UpdateState
import com.hyperos.updater.domain.usecase.CheckOtaUpdateUseCase
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
    private val checkOtaUpdateUseCase: CheckOtaUpdateUseCase,
    private val downloadUpdateUseCase: DownloadUpdateUseCase,
    private val getDeviceInfoUseCase: GetDeviceInfoUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val _currentVersion = MutableStateFlow("")
    val currentVersion: StateFlow<String> = _currentVersion

    private val _hasChecked = MutableStateFlow(false)
    val hasChecked: StateFlow<Boolean> = _hasChecked

    private val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HyperOSUpdater").also { it.mkdirs() }
    private var lastAvailableUpdate: OtaUpdate? = null

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
                val result = checkOtaUpdateUseCase()
                _hasChecked.value = true
                // Cache the update for retry
                if (result is UpdateState.Available) {
                    lastAvailableUpdate = result.update
                }
                _state.value = result
            } catch (e: Exception) {
                _state.value = UpdateState.Error(e.message ?: "Failed to check updates")
            }
        }
    }

    fun downloadUpdate(url: String, filename: String, md5: String?) {
        viewModelScope.launch {
            try {
                downloadUpdateUseCase.download(url, filename, md5, downloadsDir).collect { progress ->
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
}
