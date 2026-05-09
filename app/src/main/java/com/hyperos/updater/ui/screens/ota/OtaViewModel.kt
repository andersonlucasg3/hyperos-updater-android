package com.hyperos.updater.ui.screens.ota

import android.app.Application
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

    private val downloadsDir = File(app.filesDir, "downloads").also { it.mkdirs() }

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
                _state.value = result
            } catch (e: Exception) {
                _state.value = UpdateState.Error(e.message ?: "Failed to check updates")
            }
        }
    }

    fun downloadUpdate(url: String, filename: String, md5: String?) {
        viewModelScope.launch {
            try {
                downloadUpdateUseCase.download(url, filename, md5).collect { progress ->
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
}
