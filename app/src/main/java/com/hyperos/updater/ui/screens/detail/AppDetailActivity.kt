package com.hyperos.updater.ui.screens.detail

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.hyperos.updater.domain.model.AppType
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.ui.DownloadActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AppDetailActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_APP_TYPE = "appType"
        const val EXTRA_SEARCH_NAME = "searchName"
        const val EXTRA_SEARCH_VERSION = "searchVersion"
        const val EXTRA_SEARCH_SOURCE = "searchSource"
        const val EXTRA_SEARCH_PAGE_URL = "searchPageUrl"
        const val EXTRA_SEARCH_ICON_URL = "searchIconUrl"
        /** Pipe-separated hits: each entry is "SOURCE|VERSION|URL". */
        const val EXTRA_SEARCH_HITS = "searchHits"
    }

    private val viewModel: AppDetailViewModel by viewModels()

    // WebView pending-download state (written from Compose, read from launcher callback)
    private var pendingDlKey: String = ""
    private var pendingDlAppName: String = ""
    private var pendingDlVersion: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val appTypeStr = intent.getStringExtra(EXTRA_APP_TYPE)
        val appType = try { AppType.valueOf(appTypeStr ?: "") } catch (_: Exception) { null }

        val searchName = intent.getStringExtra(EXTRA_SEARCH_NAME)
        val searchVersion = intent.getStringExtra(EXTRA_SEARCH_VERSION)
        val searchSourceStr = intent.getStringExtra(EXTRA_SEARCH_SOURCE)
        val searchSource = try { UpdateSource.valueOf(searchSourceStr ?: "") } catch (_: Exception) { null }
        val searchPageUrl = intent.getStringExtra(EXTRA_SEARCH_PAGE_URL)
        val searchIconUrl = intent.getStringExtra(EXTRA_SEARCH_ICON_URL)

        // Parse serialized search hits: "SOURCE|VERSION|URL" per entry
        val searchHitsRaw = intent.getStringArrayListExtra(EXTRA_SEARCH_HITS)
        val searchHits = searchHitsRaw?.mapNotNull { entry ->
            val parts = entry.split("|", limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val src = try { UpdateSource.valueOf(parts[0]) } catch (_: Exception) { return@mapNotNull null }
            com.hyperos.updater.ui.screens.search.SourceHit(
                source = src,
                versionName = parts[1].ifBlank { null },
                downloadPageUrl = parts[2],
                iconUrl = null
            )
        } ?: emptyList()

        val isSearchOrigin = searchName != null && searchSource != null

        // WebView download launcher — captures CDN URL + replay headers for native OkHttp download
        val downloadLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val url = result.data?.getStringExtra(DownloadActivity.EXTRA_DOWNLOAD_URL)
                if (url != null && pendingDlKey.isNotBlank()) {
                    val referer = result.data?.getStringExtra(DownloadActivity.EXTRA_REFERER) ?: ""
                    val ua = result.data?.getStringExtra(DownloadActivity.EXTRA_USER_AGENT) ?: ""
                    val cookie = result.data?.getStringExtra(DownloadActivity.EXTRA_COOKIE) ?: ""
                    val headers = buildMap {
                        if (referer.isNotBlank()) put("Referer", referer)
                        if (ua.isNotBlank()) put("User-Agent", ua)
                        if (cookie.isNotBlank()) put("Cookie", cookie)
                    }
                    val filename = AppDetailViewModel.buildApkFileName(url, pendingDlAppName, pendingDlVersion)
                    viewModel.downloadManager.startDownload(url, filename, pendingDlKey, pendingDlAppName, headers)
                }
            }
            pendingDlKey = ""
            pendingDlAppName = ""
            pendingDlVersion = null
        }

        setContent {
            AppDetailScreen(
                viewModel = viewModel,
                onBack = { finish() },
                onDownloadViaWebView = { key, appName, version, url ->
                    pendingDlKey = key
                    pendingDlAppName = appName
                    pendingDlVersion = version
                    val intent = Intent(this, DownloadActivity::class.java)
                    intent.putExtra(DownloadActivity.EXTRA_URL, url)
                    intent.putExtra(DownloadActivity.EXTRA_APP_NAME, appName)
                    downloadLauncher.launch(intent)
                }
            )
        }

        // Load data
        if (isSearchOrigin) {
            viewModel.loadSearchOrigin(packageName, searchName!!, searchVersion, searchSource!!, searchPageUrl, searchIconUrl, searchHits)
        } else if (packageName != null && appType != null) {
            viewModel.loadInstalled(packageName, appType)
        } else {
            finish()
        }
    }
}
