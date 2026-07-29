package com.hyperos.updater.ui.screens.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyperos.updater.data.remote.ApkComboService
import com.hyperos.updater.data.remote.ApkMirrorService
import com.hyperos.updater.data.remote.ApkPureService
import com.hyperos.updater.data.remote.AptoideService
import com.hyperos.updater.data.remote.MemeOsService
import com.hyperos.updater.data.remote.UptodownService
import com.hyperos.updater.domain.DownloadManager
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.util.VersionComparator
import com.hyperos.updater.util.WearOsDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Models ──────────────────────────────────────────────────────────────────────

/** A single hit from one source for a given app. */
data class SourceHit(
    val source: UpdateSource,
    val versionName: String?,
    val downloadPageUrl: String,
    val iconUrl: String?
)

/** A grouped result: one app with hits from one or more sources. */
data class AppSearchResult(
    val appName: String,
    val iconUrl: String?,
    val devName: String?,
    val hits: List<SourceHit>,
    val displayVersion: String?,
    val bestSource: UpdateSource
)

/** Internal flat result collected from each source before grouping. */
private data class FlatResult(
    val appName: String,
    val versionName: String?,
    val source: UpdateSource,
    val downloadPageUrl: String,
    val devName: String?,
    val iconUrl: String?
)

data class AppSearchState(
    val query: String = "",
    val results: List<AppSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null
)

// ── ViewModel ───────────────────────────────────────────────────────────────────

@HiltViewModel
class AppSearchViewModel @Inject constructor(
    val downloadManager: DownloadManager,
    private val apkMirrorService: ApkMirrorService,
    private val apkPureService: ApkPureService,
    private val apkComboService: ApkComboService,
    private val aptoideService: AptoideService,
    private val memeOsService: MemeOsService,
    private val uptodownService: UptodownService
) : ViewModel() {

    val state: StateFlow<AppSearchState>
        get() = _state
    private val _state = MutableStateFlow(AppSearchState())

    private var searchId = 0

    // ── Source preference order for bestSource ──────────────────────────────────
    private val sourcePriority = listOf(
        UpdateSource.APTOIDE, UpdateSource.MEMEOS,
        UpdateSource.GITHUB, UpdateSource.FDROID,
        UpdateSource.TENCENT
    )

    fun search(query: String) {
        if (query.isBlank()) {
            _state.value = _state.value.copy(query = query, results = emptyList(), error = null)
            return
        }
        val id = ++searchId
        _state.value = _state.value.copy(query = query, isSearching = true, results = emptyList(), error = null)
        viewModelScope.launch {
            val mirror = async { tryMirrorSearch(query) }
            val pure = async { tryPureSearch(query) }
            val combo = async { tryComboSearch(query) }
            val memeos = async { tryMemeOsSearch(query) }
            val aptoide = async { tryAptoideSearch(query) }
            val uptodown = async { tryUptodownSearch(query) }

            var flat = emptyList<FlatResult>()
            val m = mirror.await().filter { !WearOsDetector.isWearOsListing(it.appName) && !WearOsDetector.isWearOsListing(it.versionName) }; if (id == searchId) { flat = m; _state.value = _state.value.copy(results = group(flat)) }
            val p = pure.await().filter { !WearOsDetector.isWearOsListing(it.appName) && !WearOsDetector.isWearOsListing(it.versionName) }; if (id == searchId) { flat = (flat + p).distinctBy { it.downloadPageUrl }; _state.value = _state.value.copy(results = group(flat)) }
            val c = combo.await().filter { !WearOsDetector.isWearOsListing(it.appName) && !WearOsDetector.isWearOsListing(it.versionName) }; if (id == searchId) { flat = (flat + c).distinctBy { it.downloadPageUrl }; _state.value = _state.value.copy(results = group(flat)) }
            val e = memeos.await().filter { !WearOsDetector.isWearOsListing(it.appName) && !WearOsDetector.isWearOsListing(it.versionName) }; if (id == searchId) { flat = (flat + e).distinctBy { it.downloadPageUrl }; _state.value = _state.value.copy(results = group(flat)) }
            val a = aptoide.await().filter { !WearOsDetector.isWearOsListing(it.appName) && !WearOsDetector.isWearOsListing(it.versionName) }; if (id == searchId) { flat = (flat + a).distinctBy { it.downloadPageUrl }; _state.value = _state.value.copy(results = group(flat)) }
            val u = uptodown.await().filter { !WearOsDetector.isWearOsListing(it.appName) && !WearOsDetector.isWearOsListing(it.versionName) }; if (id == searchId) { flat = (flat + u).distinctBy { it.downloadPageUrl }; _state.value = _state.value.copy(results = group(flat)) }
            if (id == searchId) _state.value = _state.value.copy(isSearching = false)
        }
    }

    // ── Grouping ────────────────────────────────────────────────────────────────

    private fun group(flat: List<FlatResult>): List<AppSearchResult> {
        val grouped = flat.groupBy { normalize(it.appName) }
        return grouped.values.map { items ->
            val hits = items.map { SourceHit(it.source, it.versionName, it.downloadPageUrl, it.iconUrl) }
            val iconUrl = items.firstNotNullOfOrNull { it.iconUrl }
            val devName = items.firstNotNullOfOrNull { it.devName }
            val displayVersion = pickBestVersion(items.mapNotNull { it.versionName })
            val bestSource = pickBestSource(items.map { it.source })
            AppSearchResult(
                appName = items.first().appName,
                iconUrl = iconUrl,
                devName = devName,
                hits = hits,
                displayVersion = displayVersion,
                bestSource = bestSource
            )
        }
    }

    /** Normalize for grouping: lowercase, trim, collapse non-alphanumeric chars. */
    private fun normalize(name: String): String =
        name.lowercase().trim().replace(Regex("[^a-z0-9]"), "")

    /** Pick the highest versionName across hits, or null if none have a version. */
    private fun pickBestVersion(versions: List<String>): String? {
        if (versions.isEmpty()) return null
        var best = versions.first()
        for (v in versions.drop(1)) {
            if (VersionComparator.isNewer(best, v)) best = v
        }
        return best
    }

    /** Pick the best source for quick download: preferred list first, then first available. */
    private fun pickBestSource(sources: List<UpdateSource>): UpdateSource {
        for (pref in sourcePriority) {
            if (pref in sources) return pref
        }
        return sources.firstOrNull() ?: UpdateSource.UNTRACKED
    }

    // ── Download helpers ────────────────────────────────────────────────────────

    fun downloadFromUrl(url: String, key: String, appName: String, headers: Map<String, String> = emptyMap(), version: String? = null) {
        val filename = com.hyperos.updater.ui.screens.apps.AppUpdatesViewModel.buildApkFileName(url, appName, version)
        downloadManager.startDownload(url, filename, key, appName, headers)
    }

    /** Resolve a direct signed APK URL for a MEMEOS version page, bypassing the countdown. Returns null on failure. */
    suspend fun resolveMemeOsDirectDownload(versionPageUrl: String): String? =
        memeOsService.resolveDirectDownloadUrl(versionPageUrl)

    /** Download from the best source of a grouped result. */
    fun downloadFromResult(result: AppSearchResult) {
        val best = result.hits.firstOrNull { it.source == result.bestSource } ?: return
        val key = best.source.name + result.appName
        viewModelScope.launch {
            val url = when (best.source) {
                UpdateSource.APKPURE -> {
                    val pkg = best.downloadPageUrl.split("/").lastOrNull { it.contains(".") } ?: best.downloadPageUrl
                    "https://d.apkpure.com/b/APK/$pkg?version=latest"
                }
                UpdateSource.APTOIDE -> best.downloadPageUrl
                else -> best.downloadPageUrl
            }
            downloadFromUrl(url, key, result.appName, version = best.versionName)
        }
    }

    // ── Source search methods (fail-soft) ───────────────────────────────────────

    private suspend fun tryMirrorSearch(query: String): List<FlatResult> = try {
        apkMirrorService.searchByName(query).map { item ->
            FlatResult(item.appName, item.version, UpdateSource.APKMIRROR, item.pageUrl, item.devName, item.iconUrl)
        }
    } catch (_: Exception) { emptyList() }

    private suspend fun tryPureSearch(query: String): List<FlatResult> {
        if (query.contains(".")) {
            try {
                val r = apkPureService.search(query)
                if (r != null) return listOf(FlatResult(r.appName, r.versionName, UpdateSource.APKPURE, r.downloadUrl ?: "", null, null))
            } catch (_: Exception) { }
        }
        return try {
            apkPureService.searchByName(query).map { item ->
                FlatResult(item.appName, null, UpdateSource.APKPURE, item.detailUrl, null, null)
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * APKCombo search is package-name only. Name-search at apkcombo.com/search/<query>
     * returns HTTP 403 (Cloudflare). OkHttp cannot bypass this; the download page works
     * only in WebView. The guard `!query.contains(".")` is correct — we skip name-only
     * queries because they would 403 silently.
     */
    private suspend fun tryComboSearch(query: String): List<FlatResult> {
        if (!query.contains(".")) return emptyList()
        return try {
            val r = apkComboService.search(query)
            if (r != null) listOf(FlatResult(r.appName, r.versionName, UpdateSource.APKCOMBO, r.downloadUrl ?: "", null, null))
            else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun tryAptoideSearch(query: String): List<FlatResult> = try {
        aptoideService.searchByName(query).map { item ->
            val dlUrl = item.downloadUrl ?: ""
            FlatResult(item.appName, item.versionName, UpdateSource.APTOIDE, dlUrl, null, item.iconUrl)
        }
    } catch (_: Exception) { emptyList() }

    private suspend fun tryMemeOsSearch(query: String): List<FlatResult> = try {
        // MemeOS only lists Xiaomi system apps — empty for third-party names is EXPECTED.
        val r = memeOsService.searchByName(query)
        if (r != null) listOf(FlatResult(r.appName, r.versionName, UpdateSource.MEMEOS, r.downloadUrl, null, null))
        else emptyList()
    } catch (_: Exception) { emptyList() }

    /**
     * Uptodown search is currently non-functional: all known search URL patterns
     * (android/search/<q>, /search?q=<q>, /android/buscar/<q>, etc.) return HTTP 404 or 410.
     * The site appears to have removed or relocated its search feature. The service
     * code is kept intact; when a working URL is discovered, update UptodownService.rawSearch().
     */
    private suspend fun tryUptodownSearch(query: String): List<FlatResult> = try {
        uptodownService.searchByName(query).map { item ->
            FlatResult(item.appName, item.versionName, UpdateSource.UPTODOWN, item.pageUrl, null, item.iconUrl)
        }
    } catch (_: Exception) { emptyList() }
}
