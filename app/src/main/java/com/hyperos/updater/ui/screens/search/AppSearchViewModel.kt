package com.hyperos.updater.ui.screens.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyperos.updater.data.remote.ApkComboService
import com.hyperos.updater.data.remote.ApkMirrorService
import com.hyperos.updater.data.remote.ApkPureService
import com.hyperos.updater.data.remote.AptoideService
import com.hyperos.updater.data.remote.UptodownService
import com.hyperos.updater.domain.ActiveDownload
import com.hyperos.updater.domain.DownloadManager
import com.hyperos.updater.domain.model.UpdateSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchResult(
    val appName: String,
    val versionName: String?,
    val source: UpdateSource,
    val downloadPageUrl: String,
    val devName: String?,
    val iconUrl: String?
)

data class AppSearchState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AppSearchViewModel @Inject constructor(
    val downloadManager: DownloadManager,
    private val apkMirrorService: ApkMirrorService,
    private val apkPureService: ApkPureService,
    private val apkComboService: ApkComboService,
    private val aptoideService: AptoideService,
    private val memeOsService: com.hyperos.updater.data.remote.MemeOsService,
    private val uptodownService: UptodownService
) : ViewModel() {

    val state: StateFlow<AppSearchState>
        get() = _state
    private val _state = MutableStateFlow(AppSearchState())

    private var searchId = 0

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

            var all = emptyList<SearchResult>()
            val m = mirror.await(); if (id == searchId) { all = m; _state.value = _state.value.copy(results = all) }
            val p = pure.await(); if (id == searchId) { all = (all + p).distinctBy { it.downloadPageUrl }; _state.value = _state.value.copy(results = all) }
            val c = combo.await(); if (id == searchId) { all = (all + c).distinctBy { it.downloadPageUrl }; _state.value = _state.value.copy(results = all) }
            val e = memeos.await(); if (id == searchId) { all = (all + e).distinctBy { it.downloadPageUrl }; _state.value = _state.value.copy(results = all) }
            val a = aptoide.await(); if (id == searchId) { all = (all + a).distinctBy { it.downloadPageUrl }; _state.value = _state.value.copy(results = all) }
            val u = uptodown.await(); if (id == searchId) { all = (all + u).distinctBy { it.downloadPageUrl }; _state.value = _state.value.copy(results = all) }
            if (id == searchId) _state.value = _state.value.copy(isSearching = false)
        }
    }

    private suspend fun tryMemeOsSearch(query: String): List<SearchResult> = try {
        val r = memeOsService.searchByName(query)
        if (r != null) listOf(SearchResult(r.appName, r.versionName, com.hyperos.updater.domain.model.UpdateSource.MEMEOS, r.downloadUrl, null, null))
        else emptyList()
    } catch (_: Exception) { emptyList() }

    fun downloadFromUrl(url: String, key: String, appName: String, headers: Map<String, String> = emptyMap(), version: String? = null) {
        val filename = com.hyperos.updater.ui.screens.apps.AppUpdatesViewModel.buildApkFileName(url, appName, version)
        downloadManager.startDownload(url, filename, key, appName, headers)
    }

    /** Resolve a direct signed APK URL for a MEMEOS version page, bypassing the countdown. Returns null on failure. */
    suspend fun resolveMemeOsDirectDownload(versionPageUrl: String): String? =
        memeOsService.resolveDirectDownloadUrl(versionPageUrl)

    fun downloadFromPage(result: SearchResult) {
        val key = result.source.name + result.appName
        viewModelScope.launch {
            val url = when (result.source) {
                UpdateSource.APKPURE -> {
                    val pkg = result.downloadPageUrl.split("/").lastOrNull { it.contains(".") } ?: result.downloadPageUrl
                    "https://d.apkpure.com/b/APK/$pkg?version=latest"
                }
                UpdateSource.APTOIDE -> result.downloadPageUrl // Aptoide provides direct APK URLs — no WebView needed
                else -> result.downloadPageUrl
            }
            downloadFromUrl(url, key, result.appName, version = result.versionName)
        }
    }

    private suspend fun tryMirrorSearch(query: String): List<SearchResult> = try {
        apkMirrorService.searchByName(query).map { item ->
            SearchResult(item.appName, item.version, UpdateSource.APKMIRROR, item.pageUrl, item.devName, item.iconUrl)
        }
    } catch (_: Exception) { emptyList() }

    private suspend fun tryPureSearch(query: String): List<SearchResult> {
        if (query.contains(".")) {
            try {
                val r = apkPureService.search(query)
                if (r != null) return listOf(SearchResult(r.appName, r.versionName, UpdateSource.APKPURE, r.downloadUrl ?: "", null, null))
            } catch (_: Exception) { }
        }
        return try {
            apkPureService.searchByName(query).map { item ->
                SearchResult(item.appName, null, UpdateSource.APKPURE, item.detailUrl, null, null)
            }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun tryComboSearch(query: String): List<SearchResult> {
        if (!query.contains(".")) return emptyList()
        return try {
            val r = apkComboService.search(query)
            if (r != null) listOf(SearchResult(r.appName, r.versionName, UpdateSource.APKCOMBO, r.downloadUrl ?: "", null, null))
            else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun tryAptoideSearch(query: String): List<SearchResult> = try {
        aptoideService.searchByName(query).map { item ->
            val dlUrl = item.downloadUrl ?: ""
            SearchResult(item.appName, item.versionName, UpdateSource.APTOIDE, dlUrl, null, item.iconUrl)
        }
    } catch (_: Exception) { emptyList() }

    private suspend fun tryUptodownSearch(query: String): List<SearchResult> = try {
        uptodownService.searchByName(query).map { item ->
            SearchResult(item.appName, item.versionName, UpdateSource.UPTODOWN, item.pageUrl, null, item.iconUrl)
        }
    } catch (_: Exception) { emptyList() }
}
