package com.hyperos.updater.ui.screens.apps

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyperos.updater.data.local.dao.TrackedAppDao
import com.hyperos.updater.data.local.entity.TrackedAppEntity
import com.hyperos.updater.domain.DownloadManager
import com.hyperos.updater.domain.model.AppUpdate
import com.hyperos.updater.domain.model.AppType
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.domain.repository.AppUpdateRepository
import com.hyperos.updater.domain.repository.PreferencesRepository
import com.hyperos.updater.domain.usecase.CheckSystemAppUpdatesUseCase
import com.hyperos.updater.domain.usecase.CheckThirdPartyAppUpdatesUseCase
import com.hyperos.updater.ui.components.DownloadStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppUpdatesViewModel @Inject constructor(
    private val app: Application,
    private val trackedAppDao: TrackedAppDao,
    private val checkSystemAppUpdatesUseCase: CheckSystemAppUpdatesUseCase,
    private val checkThirdPartyAppUpdatesUseCase: CheckThirdPartyAppUpdatesUseCase,
    val downloadManager: DownloadManager,
    private val memeOsService: com.hyperos.updater.data.remote.MemeOsService,
    private val preferencesRepository: PreferencesRepository,
    private val appUpdateRepository: AppUpdateRepository
) : ViewModel() {

    // Compose-optimized list: element-level change tracking, O(1) element updates
    val appList = mutableStateListOf<AppUpdate>()
    // Package → index for O(1) lookup
    private val pkgIndex = mutableMapOf<String, Int>()

    // Persisted blacklist & skipped versions — reactive from DataStore
    val blacklistedPackages: StateFlow<Set<String>> = preferencesRepository.blacklistedPackages
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val skippedVersions: StateFlow<Set<String>> = preferencesRepository.skippedVersions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // Persisted "Updatable" filter toggle
    val updatableFilter: StateFlow<Boolean> = preferencesRepository.updatableFilterEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setUpdatableFilter(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setUpdatableFilterEnabled(enabled) }
    }

    // Persisted "show system apps" toggle — off = user apps only
    val showSystemApps: StateFlow<Boolean> = preferencesRepository.showSystemApps
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowSystemApps(show: Boolean) {
        viewModelScope.launch { preferencesRepository.setShowSystemApps(show) }
    }

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    /** Scan progress: (apps checked, total apps) — null when not scanning. */
    private val _scanProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val scanProgress: StateFlow<Pair<Int, Int>?> = _scanProgress.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _checkingApps = MutableStateFlow<Set<String>>(emptySet())
    val checkingApps: StateFlow<Set<String>> = _checkingApps.asStateFlow()

    private var checkJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            val saved = trackedAppDao.getAll()
            if (saved.isNotEmpty()) {
                saved.forEach { entity ->
                    val update = entityToAppUpdate(entity)
                    // Reset latestVersion until fresh scan confirms updates
                    val neutral = update.copy(latestVersion = update.currentVersion)
                    pkgIndex[neutral.packageName] = appList.size
                    appList.add(neutral)
                }
            }
        }
        viewModelScope.launch {
            downloadManager.downloads.collect { downloads ->
                downloads.forEach { (key, dl) ->
                    if (dl.progress.status == DownloadStatus.COMPLETED) {
                        val idx = pkgIndex.values.firstOrNull { i ->
                            val u = appList.getOrNull(i) ?: return@firstOrNull false
                            // Match primary source key or any sourceVersion key
                            (u.updateSource.name + u.appName) == key ||
                            u.sourceVersions.any { sv -> (sv.source.name + u.appName) == key }
                        } ?: return@forEach
                        val update = appList[idx]
                        appList[idx] = update.copy(currentVersion = update.latestVersion)
                        viewModelScope.launch {
                            trackedAppDao.updateCurrentVersion(update.packageName, update.latestVersion, System.currentTimeMillis())
                        }
                        downloadManager.dismissDownload(key)
                    }
                }
            }
        }
    }

    private var autoCheckDone = false

    /** Auto-scan on first open only — tab switches must not retrigger a full scan. */
    fun checkAllAppsIfNeeded() {
        if (autoCheckDone) return
        autoCheckDone = true
        checkAllApps()
    }

    fun checkAllApps() {
        checkJob?.cancel()
        _isScanning.value = true
        _error.value = null
        checkJob = viewModelScope.launch {
            // "Sistema" filter also scopes the scan: off = user apps only.
            // Read the pref directly — the StateFlow starts with the default (true)
            // until DataStore emits, which would race the auto-scan on cold start.
            val includeSystem = preferencesRepository.showSystemApps.first()
            // Total = installed apps in scope, so the UI can show x/y
            val total = try {
                (if (includeSystem) appUpdateRepository.getInstalledApps(com.hyperos.updater.domain.model.AppType.SYSTEM).size else 0) +
                    appUpdateRepository.getInstalledApps(com.hyperos.updater.domain.model.AppType.THIRD_PARTY).size
            } catch (_: Exception) { 0 }
            var done = 0
            if (total > 0) _scanProgress.value = 0 to total
            fun bumped() { if (total > 0) _scanProgress.value = ++done to total }

            val scanned = mutableSetOf<String>()
            val systemJob = if (includeSystem) launch {
                checkSystemAppUpdatesUseCase().collect { update -> upsert(update); scanned.add(update.packageName); bumped() }
            } else null
            val thirdPartyJob = launch {
                checkThirdPartyAppUpdatesUseCase().collect { update -> upsert(update); scanned.add(update.packageName); bumped() }
            }
            systemJob?.join()
            thirdPartyJob.join()

            // Remove stale entries — but only for app types that were in scan scope
            val toRemove = appList.mapIndexedNotNull { i, u ->
                val inScope = includeSystem || u.appType != com.hyperos.updater.domain.model.AppType.SYSTEM
                if (inScope && u.packageName !in scanned) i else null
            }.sortedDescending()
            toRemove.forEach { i ->
                val pkg = appList[i].packageName
                pkgIndex.remove(pkg)
                appList.removeAt(i)
            }
            // Fix indices after removals
            pkgIndex.clear()
            appList.forEachIndexed { i, u -> pkgIndex[u.packageName] = i }

            // Persist
            val now = System.currentTimeMillis()
            appList.forEach { update ->
                trackedAppDao.upsert(TrackedAppEntity(
                    packageName = update.packageName, appName = update.appName,
                    currentVersion = update.currentVersion, latestVersion = update.latestVersion,
                    latestVersionCode = update.latestVersionCode,
                    appType = update.appType.name, updateSource = update.updateSource.name,
                    apkMirrorSlug = null, lastCheckedAt = now
                ))
            }
            _isScanning.value = false
            _scanProgress.value = null
        }
    }

    private fun upsert(update: AppUpdate) {
        val idx = pkgIndex[update.packageName]
        if (idx != null && idx < appList.size && appList[idx].packageName == update.packageName) {
            // Update in place — Compose tracks element-level changes
            appList[idx] = update
        } else {
            // New entry
            pkgIndex[update.packageName] = appList.size
            appList.add(update)
        }
    }

    fun openSourcePage(update: AppUpdate) {
        val url = update.downloadUrl ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        } catch (e: Exception) { _error.value = "No browser available" }
    }

    fun getDownloadPageUrl(update: AppUpdate): String = getSourcePageUrl(update.packageName, update.updateSource, update.downloadUrl)

    /** Get the download page URL for a specific source. */
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
        /** Extract a usable filename from a download URL, handling CDN redirect URLs. */
        fun extractFilename(url: String): String {
            // Try _fn query param (base64-encoded filename used by some CDNs)
            val fnParam = Regex("[?&]_fn=([^&]+)").find(url)?.groupValues?.get(1)
            if (fnParam != null) {
                try {
                    val decoded = android.util.Base64.decode(fnParam, android.util.Base64.URL_SAFE)
                    val name = String(decoded, Charsets.UTF_8).replace("+", " ")
                    if (name.isNotBlank()) return name
                } catch (_: Exception) { }
            }
            // Fallback: last path segment before query
            return url.split("/").lastOrNull()?.substringBefore("?")
                ?.takeIf { it.isNotBlank() } ?: "downloaded.apk"
        }

        private val ARCHIVE_EXTS = setOf("apk", "apkm", "xapk", "apks", "aab")
        private val GENERIC_NAMES = setOf("download", "file", "apk", "index", "redirect", "dl", "get", "downloaded")

        /**
         * Build a filename for a downloaded archive. Uses the URL's own filename when it
         * looks meaningful (real name + archive extension); otherwise builds one from the
         * app context: `<AppName>-<version>.<ext>`. We always know the app/version at the
         * call site, so a context-free CDN URL never produces a broken name.
         */
        fun buildApkFileName(url: String, appName: String, version: String?): String {
            val path = url.substringBefore('?').substringBefore('#')
            val segment = path.substringAfterLast('/')
            val ext = segment.substringAfterLast('.', "").lowercase()
            if (ext in ARCHIVE_EXTS) {
                val base = segment.substringBeforeLast('.')
                val looksGeneric = base.lowercase() in GENERIC_NAMES ||
                    base.length <= 3 || base.none { it.isLetter() } ||
                    (base.length >= 24 && base.all { it.isLetterOrDigit() }) // raw hash/blob
                if (!looksGeneric) return segment
            }
            val safeExt = if (ext in ARCHIVE_EXTS) ext else "apk"
            val safeApp = appName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "app" }
            val safeVer = version?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.takeIf { it.isNotBlank() }
            return if (safeVer != null) "$safeApp-$safeVer.$safeExt" else "$safeApp.$safeExt"
        }
    }

    /** Resolve a direct signed APK URL for a MEMEOS version page, bypassing the countdown. Returns null on failure. */
    suspend fun resolveMemeOsDirectDownload(versionPageUrl: String): String? =
        memeOsService.resolveDirectDownloadUrl(versionPageUrl)

    /** Persistently hide an app from the updates list. */
    fun hideApp(packageName: String) {
        viewModelScope.launch { preferencesRepository.addToBlacklist(packageName) }
    }

    /** Skip a specific version of an app. If a different version appears later, the update shows again. */
    fun skipVersion(packageName: String, versionName: String) {
        viewModelScope.launch { preferencesRepository.skipVersion(packageName, versionName) }
    }

    /** Re-check a single app for updates without triggering a full scan. */
    fun recheckApp(update: AppUpdate) {
        val key = update.packageName + update.appType.name
        if (key in _checkingApps.value) return
        _checkingApps.value = _checkingApps.value + key
        viewModelScope.launch {
            try {
                val result = appUpdateRepository.recheckApp(update.packageName, update.appType)
                upsert(result)
            } catch (e: Exception) {
                _error.value = "Recheck failed for ${update.appName}: ${e.message}"
            } finally {
                _checkingApps.value = _checkingApps.value - key
            }
        }
    }

    private fun entityToAppUpdate(e: TrackedAppEntity) = AppUpdate(
        packageName = e.packageName, appName = e.appName,
        currentVersion = e.currentVersion, latestVersion = e.latestVersion ?: e.currentVersion,
        latestVersionCode = e.latestVersionCode ?: 0L,
        fileSize = null, downloadUrl = null, changelog = null, publishedDate = null,
        updateSource = try { UpdateSource.valueOf(e.updateSource) } catch (_: Exception) { UpdateSource.UNTRACKED },
        appType = try { AppType.valueOf(e.appType) } catch (_: Exception) { AppType.THIRD_PARTY }
    )
}
