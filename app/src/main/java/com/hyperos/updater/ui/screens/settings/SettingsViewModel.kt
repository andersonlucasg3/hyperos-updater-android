package com.hyperos.updater.ui.screens.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyperos.updater.BuildConfig
import com.hyperos.updater.data.remote.SelfUpdateRelease
import com.hyperos.updater.data.remote.SelfUpdateService
import com.hyperos.updater.domain.DownloadManager
import com.hyperos.updater.domain.installer.RootApkInstaller
import com.hyperos.updater.domain.repository.PreferencesRepository
import com.hyperos.updater.domain.ActiveDownload
import com.hyperos.updater.util.LogShareHelper
import com.hyperos.updater.util.VersionComparator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface SelfUpdateState {
    data object Idle : SelfUpdateState
    data object Checking : SelfUpdateState
    data object UpToDate : SelfUpdateState
    data class Available(val release: SelfUpdateRelease) : SelfUpdateState
    data class Error(val message: String) : SelfUpdateState
    data object NoRelease : SelfUpdateState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val rootInstaller: RootApkInstaller,
    private val selfUpdateService: SelfUpdateService,
    val downloadManager: DownloadManager
) : ViewModel() {

    val checkInterval: StateFlow<Int> = preferencesRepository.checkIntervalHours
        .stateIn(viewModelScope, SharingStarted.Eagerly, 24)

    val autoUpdateEnabled: StateFlow<Boolean> = preferencesRepository.autoUpdateEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val blacklistedPackages: StateFlow<Set<String>> = preferencesRepository.blacklistedPackages
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val skippedVersions: StateFlow<Set<String>> = preferencesRepository.skippedVersions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _rootAvailable = MutableStateFlow<Boolean?>(null)
    val rootAvailable: StateFlow<Boolean?> = _rootAvailable

    /** Human-readable per-candidate su probe results, shown in the UI for debugging. */
    private val _rootDiagnosis = MutableStateFlow<String?>(null)
    val rootDiagnosis: StateFlow<String?> = _rootDiagnosis

    // ── Self-update state ────────────────────────────────────────

    private val _selfUpdateState = MutableStateFlow<SelfUpdateState>(SelfUpdateState.Idle)
    val selfUpdateState: StateFlow<SelfUpdateState> = _selfUpdateState

    /** Filtered download entry for the SELFUPDATE key. */
    val selfUpdateDownload: StateFlow<ActiveDownload?> =
        downloadManager.downloads.map { it["SELFUPDATE"] }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        refreshRootStatus()
    }

    fun checkSelfUpdate() {
        if (_selfUpdateState.value is SelfUpdateState.Checking) return
        _selfUpdateState.value = SelfUpdateState.Checking
        viewModelScope.launch {
            try {
                val release = selfUpdateService.checkLatestRelease()
                if (release == null) {
                    _selfUpdateState.value = SelfUpdateState.NoRelease
                    return@launch
                }
                if (release.apkUrl == null) {
                    _selfUpdateState.value = SelfUpdateState.Error("release sem asset .apk")
                    return@launch
                }
                val isNewer = VersionComparator.isNewer(BuildConfig.VERSION_NAME, release.version)
                if (isNewer) {
                    _selfUpdateState.value = SelfUpdateState.Available(release)
                } else {
                    _selfUpdateState.value = SelfUpdateState.UpToDate
                }
            } catch (e: Exception) {
                _selfUpdateState.value = SelfUpdateState.Error(e.message ?: "erro desconhecido")
            }
        }
    }

    fun refreshRootStatus() {
        viewModelScope.launch {
            // Force re-check by resetting cached state
            rootInstaller.resetAvailability()
            val diag = rootInstaller.diagnoseAvailability(promptTimeoutSeconds = 10, probeTimeoutSeconds = 5)
            _rootAvailable.value = diag.available
            _rootDiagnosis.value = diag.detail
        }
    }

    fun requestRootAccess() {
        viewModelScope.launch {
            // Generous timeout on the first candidate: the su call should surface the
            // KernelSU/Magisk grant dialog and the user needs time to tap "Allow".
            rootInstaller.resetAvailability()
            val diag = rootInstaller.diagnoseAvailability(promptTimeoutSeconds = 60, probeTimeoutSeconds = 5)
            _rootAvailable.value = diag.available
            _rootDiagnosis.value = diag.detail
        }
    }

    fun setCheckInterval(hours: Int) {
        viewModelScope.launch { preferencesRepository.setCheckIntervalHours(hours) }
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setAutoUpdateEnabled(enabled) }
    }

    fun unhideApp(packageName: String) {
        viewModelScope.launch { preferencesRepository.removeFromBlacklist(packageName) }
    }

    fun unskipVersion(packageName: String, versionName: String) {
        viewModelScope.launch { preferencesRepository.removeSkippedVersion(packageName, versionName) }
    }

    // ── Log sharing ──────────────────────────────────────────────────

    /** Whether log collection is in progress. */
    private val _isGeneratingLogs = MutableStateFlow(false)
    val isGeneratingLogs: StateFlow<Boolean> = _isGeneratingLogs

    /** Last error message from log collection (null = no error). */
    private val _logShareError = MutableStateFlow<String?>(null)
    val logShareError: StateFlow<String?> = _logShareError

    /** Collects logs and launches the system share sheet. */
    fun shareLogs(context: Context) {
        if (_isGeneratingLogs.value) return
        _logShareError.value = null
        _isGeneratingLogs.value = true
        viewModelScope.launch {
            try {
                val rootOk = rootInstaller.checkAvailability()
                val file = LogShareHelper.collectLogs(context, rootOk)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "Compartilhar logs")
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                withContext(Dispatchers.Main) {
                    context.startActivity(chooser)
                }
            } catch (e: Exception) {
                _logShareError.value = e.message ?: "Erro ao gerar logs"
            } finally {
                _isGeneratingLogs.value = false
            }
        }
    }
}
