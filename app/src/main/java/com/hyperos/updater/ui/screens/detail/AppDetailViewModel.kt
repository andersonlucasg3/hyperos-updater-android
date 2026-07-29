package com.hyperos.updater.ui.screens.detail

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hyperos.updater.data.remote.ApkMirrorService
import com.hyperos.updater.data.remote.ApkMirrorVersion
import com.hyperos.updater.data.remote.FDroidService
import com.hyperos.updater.data.remote.FDroidVersion
import com.hyperos.updater.data.remote.GitHubRelease
import com.hyperos.updater.data.remote.GitHubService
import com.hyperos.updater.data.remote.MemeOsService
import com.hyperos.updater.data.remote.MemeOsVersion
import com.hyperos.updater.domain.DownloadManager
import com.hyperos.updater.domain.model.AppType
import com.hyperos.updater.domain.model.AppUpdate
import com.hyperos.updater.domain.model.SourceVersion
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.domain.repository.AppUpdateRepository
import com.hyperos.updater.domain.repository.PreferencesRepository
import com.hyperos.updater.ui.components.DownloadProgress
import com.hyperos.updater.ui.components.DownloadStatus
import com.hyperos.updater.ui.components.isOngoing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject

// ── History models per source ─────────────────────────────────────────────────

data class MemeOsHistoryItem(
    val version: String,
    val versionCode: Long,
    val region: String,
    val date: String,
    val sizeBytes: Long?,
    val pageUrl: String
)

data class FDroidHistoryItem(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String?
)

data class GitHubHistoryItem(
    val tag: String,
    val name: String,
    val publishedAt: String?,
    val apkUrl: String?
)

data class ApkMirrorHistoryItem(
    val version: String,
    val pageUrl: String
)

// ── UI State ──────────────────────────────────────────────────────────────────

data class AppDetailUiState(
    // Header
    val appName: String = "",
    val packageName: String = "",
    val iconUrl: String? = null,
    val installedVersion: String? = null,
    val installedVersionCode: Long = 0L,
    val installerPackage: String? = null,
    val isSystemApp: Boolean = false,
    val isInstalled: Boolean = false,

    // Update info
    val currentVersion: String = "",
    val latestVersion: String = "",
    val primarySource: UpdateSource = UpdateSource.UNTRACKED,
    val sourceVersions: List<SourceVersion> = emptyList(),
    val isChecking: Boolean = false,

    // History per source
    val memeosHistory: List<MemeOsHistoryItem> = emptyList(),
    val fdroidHistory: List<FDroidHistoryItem> = emptyList(),
    val githubHistory: List<GitHubHistoryItem> = emptyList(),
    val apkmirrorHistory: List<ApkMirrorHistoryItem> = emptyList(),

    // History loading flags
    val isLoadingMemeosHistory: Boolean = false,
    val isLoadingFdroidHistory: Boolean = false,
    val isLoadingGithubHistory: Boolean = false,
    val isLoadingApkmirrorHistory: Boolean = false,

    // Actions
    val isHidingApp: Boolean = false,

    // Search-origin mode
    val isSearchOrigin: Boolean = false,
    val searchSource: UpdateSource? = null,
    val searchPageUrl: String? = null,

    val error: String? = null
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    private val app: Application,
    val downloadManager: DownloadManager,
    private val appUpdateRepository: AppUpdateRepository,
    private val memeOsService: MemeOsService,
    private val fDroidService: FDroidService,
    private val gitHubService: GitHubService,
    private val apkMirrorService: ApkMirrorService,
    private val preferencesRepository: PreferencesRepository
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(AppDetailUiState())
    val state: StateFlow<AppDetailUiState> = _state.asStateFlow()

    private val _skipDone = MutableStateFlow(false)
    val skipDone: StateFlow<Boolean> = _skipDone.asStateFlow()

    private val _hideDone = MutableStateFlow(false)
    val hideDone: StateFlow<Boolean> = _hideDone.asStateFlow()

    // ── Load ──────────────────────────────────────────────────────────────────

    fun loadInstalled(packageName: String, appType: AppType) {
        _state.value = AppDetailUiState(packageName = packageName, isChecking = true)
        viewModelScope.launch {
            val pm = app.packageManager
            try {
                val pkgInfo = pm.getPackageInfo(packageName, 0)
                val info = pkgInfo.applicationInfo ?: throw Exception("App not found")
                val appName = info.loadLabel(pm)?.toString() ?: packageName
                val vName = pkgInfo.versionName ?: ""
                val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
                }
                val isSys = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    || (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                val installerPkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        pm.getInstallSourceInfo(packageName).installingPackageName
                    } catch (_: Exception) { null }
                } else null

                _state.update {
                    it.copy(
                        appName = appName,
                        packageName = packageName,
                        installedVersion = vName,
                        installedVersionCode = vCode,
                        installerPackage = installerPkg,
                        isSystemApp = isSys,
                        isInstalled = true,
                        currentVersion = vName,
                        latestVersion = vName
                    )
                }

                // Run recheck
                val result = appUpdateRepository.recheckApp(packageName, appType)
                val hasUpdate = result.currentVersion != result.latestVersion

                _state.update {
                    it.copy(
                        appName = result.appName,
                        currentVersion = result.currentVersion,
                        latestVersion = if (hasUpdate) result.latestVersion else result.currentVersion,
                        primarySource = result.updateSource,
                        sourceVersions = result.sourceVersions,
                        isChecking = false
                    )
                }

                // Load history for each source async, fail-soft per source
                loadHistory(result.sourceVersions, result.appName)

            } catch (e: Exception) {
                Log.e("AppDetailVM", "Load failed", e)
                _state.update { it.copy(isChecking = false, error = e.message) }
            }
        }
    }

    fun loadSearchOrigin(
        packageName: String?,
        appName: String,
        versionName: String?,
        source: UpdateSource,
        pageUrl: String?,
        iconUrl: String?
    ) {
        _state.update {
            it.copy(
                appName = appName,
                packageName = packageName ?: "",
                iconUrl = iconUrl,
                currentVersion = versionName ?: "",
                latestVersion = versionName ?: "",
                primarySource = source,
                isSearchOrigin = true,
                searchSource = source,
                searchPageUrl = pageUrl,
                isInstalled = false
            )
        }

        // If packageName is provided, try to get installed info
        if (!packageName.isNullOrBlank()) {
            viewModelScope.launch {
                try {
                    val pm = app.packageManager
                    val pkgInfo = pm.getPackageInfo(packageName, 0)
                    val info = pkgInfo.applicationInfo ?: return@launch
                    val vName = pkgInfo.versionName ?: ""
                    val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pkgInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
                    }
                    val isSys = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        || (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                    _state.update {
                        it.copy(
                            installedVersion = vName,
                            installedVersionCode = vCode,
                            isSystemApp = isSys,
                            isInstalled = true,
                            currentVersion = vName
                        )
                    }
                } catch (_: Exception) { }
            }
        }

        // Load what history we can from the source
        loadSearchHistory(source, appName, pageUrl)
    }

    // ── History loading ───────────────────────────────────────────────────────

    private fun loadHistory(sourceVersions: List<SourceVersion>, appName: String) {
        val svSources = sourceVersions.map { it.source }.toSet()

        if (UpdateSource.MEMEOS in svSources) {
            _state.update { it.copy(isLoadingMemeosHistory = true) }
            viewModelScope.launch {
                try {
                    val history = memeOsService.getAppHistory(_state.value.packageName)
                    _state.update {
                        it.copy(
                            memeosHistory = history.map { h ->
                                MemeOsHistoryItem(h.version, h.versionCode, h.region, h.date, h.sizeBytes, h.pageUrl)
                            },
                            isLoadingMemeosHistory = false
                        )
                    }
                } catch (e: Exception) {
                    Log.d("AppDetailVM", "MemeOS history failed: ${e.message}")
                    _state.update { it.copy(isLoadingMemeosHistory = false) }
                }
            }
        }

        if (UpdateSource.FDROID in svSources) {
            _state.update { it.copy(isLoadingFdroidHistory = true) }
            viewModelScope.launch {
                try {
                    val history = fDroidService.getVersionHistory(_state.value.packageName)
                    _state.update {
                        it.copy(
                            fdroidHistory = history.map { h ->
                                FDroidHistoryItem(h.versionName, h.versionCode, h.apkUrl)
                            },
                            isLoadingFdroidHistory = false
                        )
                    }
                } catch (e: Exception) {
                    Log.d("AppDetailVM", "FDroid history failed: ${e.message}")
                    _state.update { it.copy(isLoadingFdroidHistory = false) }
                }
            }
        }

        if (UpdateSource.GITHUB in svSources) {
            _state.update { it.copy(isLoadingGithubHistory = true) }
            viewModelScope.launch {
                try {
                    val history = gitHubService.getReleaseHistory(_state.value.packageName)
                    _state.update {
                        it.copy(
                            githubHistory = history.map { h ->
                                GitHubHistoryItem(h.tag, h.name, h.publishedAt, h.apkUrl)
                            },
                            isLoadingGithubHistory = false
                        )
                    }
                } catch (e: Exception) {
                    Log.d("AppDetailVM", "GitHub history failed: ${e.message}")
                    _state.update { it.copy(isLoadingGithubHistory = false) }
                }
            }
        }

        if (UpdateSource.APKMIRROR in svSources) {
            _state.update { it.copy(isLoadingApkmirrorHistory = true) }
            viewModelScope.launch {
                try {
                    val versions = apkMirrorService.getRecentVersions(appName)
                    _state.update {
                        it.copy(
                            apkmirrorHistory = versions.map { v ->
                                ApkMirrorHistoryItem(v.version, v.pageUrl)
                            },
                            isLoadingApkmirrorHistory = false
                        )
                    }
                } catch (e: Exception) {
                    Log.d("AppDetailVM", "APKMirror history failed: ${e.message}")
                    _state.update { it.copy(isLoadingApkmirrorHistory = false) }
                }
            }
        }
    }

    private fun loadSearchHistory(source: UpdateSource, appName: String, pageUrl: String?) {
        when (source) {
            UpdateSource.APKMIRROR -> {
                _state.update { it.copy(isLoadingApkmirrorHistory = true) }
                viewModelScope.launch {
                    try {
                        // Try to get RSS feed from the search page URL
                        val slug = pageUrl?.let { Regex("/apk/([^/]+/[^/]+)/").find(it)?.groupValues?.get(1) }
                        val versions = if (slug != null) {
                            apkMirrorService.fetchAppFeed(slug).mapNotNull { item ->
                                item.version?.let { v -> ApkMirrorVersion(v, item.link) }
                            }
                        } else {
                            apkMirrorService.getRecentVersions(appName)
                        }
                        _state.update {
                            it.copy(
                                apkmirrorHistory = versions.map { v ->
                                    ApkMirrorHistoryItem(v.version, v.pageUrl)
                                },
                                isLoadingApkmirrorHistory = false
                            )
                        }
                    } catch (e: Exception) {
                        Log.d("AppDetailVM", "APKMirror search history failed: ${e.message}")
                        _state.update { it.copy(isLoadingApkmirrorHistory = false) }
                    }
                }
            }
            UpdateSource.MEMEOS -> {
                // For search-origin MEMEOS, try to resolve the packageName from the pageUrl
                _state.update { it.copy(isLoadingMemeosHistory = true) }
                viewModelScope.launch {
                    try {
                        val pkg = pageUrl?.let {
                            Regex("/apps/([a-zA-Z][^\"'{}\\s/]+)").find(it)?.groupValues?.get(1)
                        }
                        if (pkg != null) {
                            val history = memeOsService.getAppHistory(pkg)
                            _state.update {
                                it.copy(
                                    memeosHistory = history.map { h ->
                                        MemeOsHistoryItem(h.version, h.versionCode, h.region, h.date, h.sizeBytes, h.pageUrl)
                                    },
                                    isLoadingMemeosHistory = false
                                )
                            }
                        } else {
                            _state.update { it.copy(isLoadingMemeosHistory = false) }
                        }
                    } catch (e: Exception) {
                        _state.update { it.copy(isLoadingMemeosHistory = false) }
                    }
                }
            }
            else -> { /* No cheap history for other sources in search mode */ }
        }
    }

    // ── Recheck ───────────────────────────────────────────────────────────────

    fun recheck() {
        val s = _state.value
        if (s.isSearchOrigin || s.packageName.isBlank()) return
        val appType = if (s.isSystemApp) AppType.SYSTEM else AppType.THIRD_PARTY
        _state.update { it.copy(isChecking = true) }
        viewModelScope.launch {
            try {
                val result = appUpdateRepository.recheckApp(s.packageName, appType)
                val hasUpdate = result.currentVersion != result.latestVersion
                _state.update {
                    it.copy(
                        appName = result.appName,
                        currentVersion = result.currentVersion,
                        latestVersion = if (hasUpdate) result.latestVersion else result.currentVersion,
                        primarySource = result.updateSource,
                        sourceVersions = result.sourceVersions,
                        isChecking = false
                    )
                }
                loadHistory(result.sourceVersions, result.appName)
            } catch (e: Exception) {
                _state.update { it.copy(isChecking = false, error = e.message) }
            }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun skipVersion() {
        val s = _state.value
        viewModelScope.launch {
            preferencesRepository.skipVersion(s.packageName, s.latestVersion)
            _skipDone.value = true
        }
    }

    fun hideApp() {
        val s = _state.value
        _state.update { it.copy(isHidingApp = true) }
        viewModelScope.launch {
            preferencesRepository.addToBlacklist(s.packageName)
            _state.update { it.copy(isHidingApp = false) }
            _hideDone.value = true
        }
    }

    fun openSourcePage(url: String?) {
        if (url.isNullOrBlank()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        } catch (_: Exception) { }
    }

    // ── Download routing (mirrors the tabs exactly) ───────────────────────────

    /** Start a download with the correct routing rules for a source+URL pair. */
    fun downloadFromSource(
        source: UpdateSource,
        downloadUrl: String?,
        appName: String,
        version: String,
        pendingLauncher: (String, String, String, String) -> Unit
    ) {
        val key = source.name + appName
        if (downloadManager.installCached(key, appName)) return

        val url = downloadUrl ?: return

        viewModelScope.launch {
            // MEMEOS: try direct resolution first, fall back to WebView
            if (source == UpdateSource.MEMEOS) {
                val directUrl = memeOsService.resolveDirectDownloadUrl(url)
                if (directUrl != null) {
                    val filename = buildApkFileName(directUrl, appName, version)
                    downloadManager.startDownload(directUrl, filename, key, appName)
                    return@launch
                }
                pendingLauncher(key, appName, version, url)
                return@launch
            }

            // Direct-download sources
            val hasDirectUrl = source == UpdateSource.APTOIDE ||
                source == UpdateSource.GITHUB ||
                source == UpdateSource.FDROID ||
                source == UpdateSource.TENCENT

            if (hasDirectUrl) {
                val filename = buildApkFileName(url, appName, version)
                downloadManager.startDownload(url, filename, key, appName)
                return@launch
            }

            // WebView sources: APKMIRROR, APKCOMBO, APKPURE, UPTODOWN
            pendingLauncher(key, appName, version, url)
        }
    }

    /** Get the download page URL for a specific source, matching the tabs' routing. */
    fun getSourcePageUrl(packageName: String, source: UpdateSource, sourceDownloadUrl: String?): String {
        return when (source) {
            UpdateSource.APKPURE -> "https://apkpure.com/apk/$packageName"
            UpdateSource.APKCOMBO -> sourceDownloadUrl ?: "https://apkcombo.com/search/$packageName"
            UpdateSource.APKMIRROR -> {
                val pageUrl = sourceDownloadUrl ?: return "https://www.apkmirror.com/?s=$packageName&post_type=app_release"
                val base = pageUrl.trimEnd('/')
                val slug = base.split("/").last { it.isNotBlank() }
                "$base/${slug.replace("-release", "-android-apk-download")}/"
            }
            UpdateSource.MEMEOS -> sourceDownloadUrl ?: "https://memeosupdates.com/apps/$packageName"
            UpdateSource.APTOIDE -> sourceDownloadUrl ?: "https://en.aptoide.com/search?query=$packageName"
            UpdateSource.UPTODOWN -> sourceDownloadUrl ?: "https://www.uptodown.com/android/search/$packageName"
            UpdateSource.GITHUB, UpdateSource.FDROID, UpdateSource.TENCENT -> sourceDownloadUrl ?: "https://apkpure.com/apk/$packageName"
            else -> "https://apkpure.com/apk/$packageName"
        }
    }

    companion object {
        fun buildApkFileName(url: String, appName: String, version: String?): String {
            val path = url.substringBefore('?').substringBefore('#')
            val segment = path.substringAfterLast('/')
            val ext = segment.substringAfterLast('.', "").lowercase()
            val archiveExts = setOf("apk", "apkm", "xapk", "apks", "aab")
            val genericNames = setOf("download", "file", "apk", "index", "redirect", "dl", "get", "downloaded")

            if (ext in archiveExts) {
                val base = segment.substringBeforeLast('.')
                val looksGeneric = base.lowercase() in genericNames ||
                    base.length <= 3 || base.none { it.isLetter() } ||
                    (base.length >= 24 && base.all { it.isLetterOrDigit() })
                if (!looksGeneric) return segment
            }
            val safeExt = if (ext in archiveExts) ext else "apk"
            val safeApp = appName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "app" }
            val safeVer = version?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.takeIf { it.isNotBlank() }
            return if (safeVer != null) "$safeApp-$safeVer.$safeExt" else "$safeApp.$safeExt"
        }
    }
}
