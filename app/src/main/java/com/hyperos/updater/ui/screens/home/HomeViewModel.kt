package com.hyperos.updater.ui.screens.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

data class HomeUiState(
    val isChecking: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val app: Application,
    private val checkOtaUpdateUseCase: CheckOtaUpdateUseCase,
    private val downloadUpdateUseCase: DownloadUpdateUseCase,
    private val getDeviceInfoUseCase: GetDeviceInfoUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private val _otaState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val otaState: StateFlow<UpdateState> = _otaState

    private val _currentVersion = MutableStateFlow("")
    val currentVersion: StateFlow<String> = _currentVersion

    private val downloadsDir = File(app.filesDir, "downloads").also { it.mkdirs() }

    init {
        viewModelScope.launch {
            try {
                val device = getDeviceInfoUseCase()
                _currentVersion.value = device.miuiVersion
                Log.d("HomeVM", "Device: ${device.codename}, version: ${device.miuiVersion}")
            } catch (e: Exception) {
                Log.e("HomeVM", "Failed to get device info", e)
                _currentVersion.value = "unknown"
            }
        }
    }

    fun checkOta() {
        viewModelScope.launch {
            _otaState.value = UpdateState.Checking
            try {
                Log.d("HomeVM", "Starting OTA check...")
                val result = checkOtaUpdateUseCase()
                Log.d("HomeVM", "OTA check result: $result")
                _otaState.value = result
            } catch (e: Exception) {
                Log.e("HomeVM", "OTA check failed", e)
                _otaState.value = UpdateState.Error(e.message ?: "Check failed")
            }
        }
    }

    fun downloadOta(url: String, filename: String, md5: String?) {
        viewModelScope.launch {
            try {
                Log.d("HomeVM", "Downloading OTA: $filename")
                downloadUpdateUseCase.download(url, filename, md5).collect { progress ->
                    _otaState.value = UpdateState.Downloading(
                        progress = progress.progress,
                        bytesDownloaded = progress.bytesDownloaded,
                        totalBytes = progress.totalBytes
                    )
                }
                _otaState.value = UpdateState.ReadyToInstall(
                    File(downloadsDir, filename).absolutePath,
                    filename
                )
            } catch (e: Exception) {
                Log.e("HomeVM", "Download failed", e)
                _otaState.value = UpdateState.Error(e.message ?: "Download failed")
            }
        }
    }
}
