package com.hyperos.updater.ui.screens.detail

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyperos.updater.data.local.dao.TrackedAppDao
import com.hyperos.updater.data.local.dao.UpdateHistoryDao
import com.hyperos.updater.data.local.entity.UpdateHistoryEntity
import com.hyperos.updater.domain.installer.ApkInstaller
import com.hyperos.updater.domain.model.AppUpdate
import com.hyperos.updater.domain.model.AppType
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.domain.usecase.DownloadUpdateUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Named

data class AppDetailState(
    val isLoading: Boolean = true,
    val appUpdate: AppUpdate? = null,
    val history: List<UpdateHistoryEntity> = emptyList(),
    val downloadProgress: Int? = null,
    val error: String? = null
)

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    private val app: Application,
    private val trackedAppDao: TrackedAppDao,
    private val updateHistoryDao: UpdateHistoryDao,
    private val downloadUpdateUseCase: DownloadUpdateUseCase,
    @Named("shizuku") private val shizukuInstaller: ApkInstaller,
    @Named("fallback") private val fallbackInstaller: ApkInstaller
) : ViewModel() {

    private val _state = MutableStateFlow(AppDetailState())
    val state: StateFlow<AppDetailState> = _state

    private val downloadsDir = File(app.filesDir, "downloads").also { it.mkdirs() }

    fun load(packageName: String) {
        viewModelScope.launch {
            _state.value = AppDetailState(isLoading = true)
            try {
                val pm = app.packageManager
                val pkgInfo = pm.getPackageInfo(packageName, 0)
                val appInfo = pkgInfo.applicationInfo ?: throw Exception("App not found")
                val appName = appInfo.loadLabel(pm)?.toString() ?: packageName
                val versionName = pkgInfo.versionName ?: "0"

                val tracked = trackedAppDao.getByPackage(packageName)
                val history = updateHistoryDao.getByPackage(packageName)

                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                val appUpdate = AppUpdate(
                    packageName = packageName,
                    appName = appName,
                    currentVersion = versionName,
                    latestVersion = tracked?.latestVersion ?: versionName,
                    latestVersionCode = tracked?.latestVersionCode ?: 0L,
                    fileSize = null,
                    downloadUrl = tracked?.let {
                        // URL is not stored (ephemeral), set null
                        null
                    },
                    changelog = null,
                    publishedDate = null,
                    updateSource = tracked?.updateSource?.let {
                        try { UpdateSource.valueOf(it) } catch (_: Exception) { UpdateSource.UNTRACKED }
                    } ?: UpdateSource.UNTRACKED,
                    appType = if (isSystem) AppType.SYSTEM else AppType.THIRD_PARTY
                )

                _state.value = AppDetailState(isLoading = false, appUpdate = appUpdate, history = history)
            } catch (e: Exception) {
                _state.value = AppDetailState(isLoading = false, error = e.message)
            }
        }
    }

    fun openSourcePage() {
        val pkg = _state.value.appUpdate?.packageName ?: return
        try {
            val url = "https://apkpure.com/search?q=$pkg"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        } catch (_: Exception) { }
    }

    fun downloadAndInstall() {
        val update = _state.value.appUpdate ?: return
        viewModelScope.launch {
            try {
                val url = update.downloadUrl ?: run {
                    _state.value = _state.value.copy(error = "No download URL available")
                    return@launch
                }
                val filename = "${update.packageName}_${update.latestVersion}.apk"
                Log.d("DetailVM", "Downloading: $url → $filename")

                downloadUpdateUseCase.download(url, filename, null).collect { progress ->
                    _state.value = _state.value.copy(downloadProgress = progress.progress)
                }
                val file = File(downloadsDir, filename)
                if (!file.exists() || file.length() == 0L) {
                    _state.value = _state.value.copy(error = "Download failed: empty file")
                    return@launch
                }

                val isSystemApp = update.appType == AppType.SYSTEM
                val result = shizukuInstaller.install(file, update.packageName, isSystemApp)
                if (result is com.hyperos.updater.domain.installer.InstallResult.ShizukuNotAvailable) {
                    fallbackInstaller.install(file, update.packageName, isSystemApp)
                }

                updateHistoryDao.insert(
                    UpdateHistoryEntity(
                        packageName = update.packageName,
                        versionFrom = update.currentVersion,
                        versionTo = update.latestVersion,
                        installedAt = System.currentTimeMillis(),
                        installMethod = "PACKAGE_MANAGER"
                    )
                )
                _state.value = _state.value.copy(downloadProgress = null)
            } catch (e: Exception) {
                Log.e("DetailVM", "Install failed", e)
                _state.value = _state.value.copy(error = e.message, downloadProgress = null)
            }
        }
    }
}
