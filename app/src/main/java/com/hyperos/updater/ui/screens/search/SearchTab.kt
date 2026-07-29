package com.hyperos.updater.ui.screens.search

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.ui.components.DownloadStatus
import com.hyperos.updater.ui.components.SourceBadge
import com.hyperos.updater.ui.components.isOngoing
import com.hyperos.updater.util.toHumanReadableSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTab(
    viewModel: AppSearchViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val downloads by viewModel.downloadManager.downloads.collectAsState()
    val context = LocalContext.current
    var pendingKey by remember { mutableStateOf("") }
    var pendingAppName by remember { mutableStateOf("") }
    var pendingVersion by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val url = result.data?.getStringExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_DOWNLOAD_URL)
            if (url != null && pendingKey.isNotBlank()) {
                val referer = result.data?.getStringExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_REFERER) ?: ""
                val ua = result.data?.getStringExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_USER_AGENT) ?: ""
                val cookie = result.data?.getStringExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_COOKIE) ?: ""
                val headers = buildMap {
                    if (referer.isNotBlank()) put("Referer", referer)
                    if (ua.isNotBlank()) put("User-Agent", ua)
                    if (cookie.isNotBlank()) put("Cookie", cookie)
                }
                viewModel.downloadFromUrl(url, pendingKey, pendingAppName, headers, version = pendingVersion)
            }
        }
        pendingKey = ""
        pendingAppName = ""
        pendingVersion = null
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Find & Install") })
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.search(it) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Search apps by name...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            if (state.isSearching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }

            if (!state.isSearching && state.query.isNotBlank() && state.results.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No results found", style = MaterialTheme.typography.bodyLarge)
                }
            }

            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.results, key = { it.source.name + it.downloadPageUrl }) { result ->
                    val dlKey = result.source.name + result.appName
                    val dl = downloads[dlKey]

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (dl?.progress?.status == DownloadStatus.ERROR && dl.progress.errorMessage != null) {
                                Text("Erro: ${dl.progress.errorMessage}", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).clickable {
                                    val intent = Intent(context, com.hyperos.updater.ui.screens.detail.AppDetailActivity::class.java)
                                    intent.putExtra(com.hyperos.updater.ui.screens.detail.AppDetailActivity.EXTRA_SEARCH_NAME, result.appName)
                                    result.versionName?.let { intent.putExtra(com.hyperos.updater.ui.screens.detail.AppDetailActivity.EXTRA_SEARCH_VERSION, it) }
                                    intent.putExtra(com.hyperos.updater.ui.screens.detail.AppDetailActivity.EXTRA_SEARCH_SOURCE, result.source.name)
                                    intent.putExtra(com.hyperos.updater.ui.screens.detail.AppDetailActivity.EXTRA_SEARCH_PAGE_URL, result.downloadPageUrl)
                                    result.iconUrl?.let { intent.putExtra(com.hyperos.updater.ui.screens.detail.AppDetailActivity.EXTRA_SEARCH_ICON_URL, it) }
                                    context.startActivity(intent)
                                }) {
                                    com.hyperos.updater.ui.components.UrlAppIcon(result.iconUrl)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(result.appName, style = MaterialTheme.typography.titleMedium)
                                        if (result.versionName != null) Text("v${result.versionName}", style = MaterialTheme.typography.bodySmall)
                                        Row {
                                            SourceBadge(result.source)
                                            if (!result.devName.isNullOrBlank()) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(result.devName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                    }
                                }
                                }

                                if (dl != null) {
                                    when (dl.progress.status) {
                                        DownloadStatus.INSTALLING ->
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                                Text("Instalando...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                            }
                                        DownloadStatus.DOWNLOADING, DownloadStatus.PREPARING ->
                                            IconButton(onClick = { viewModel.downloadManager.cancelDownload(dlKey) }) {
                                                Icon(Icons.Default.Cancel, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
                                            }
                                        DownloadStatus.AWAITING_INSTALL ->
                                            IconButton(onClick = { viewModel.downloadManager.retryInstall(dlKey) }) {
                                                Icon(Icons.Default.InstallMobile, contentDescription = "Install", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        DownloadStatus.COMPLETED ->
                                            IconButton(onClick = { viewModel.downloadManager.dismissDownload(dlKey) }) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        DownloadStatus.ERROR, DownloadStatus.CANCELLED ->
                                            IconButton(onClick = { viewModel.downloadManager.dismissDownload(dlKey) }) {
                                                Icon(Icons.Default.Error, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.error)
                                            }
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(onClick = {
                                            if (result.source == UpdateSource.APKMIRROR || result.source == UpdateSource.MEMEOS || result.source == UpdateSource.UPTODOWN || result.source == UpdateSource.APKCOMBO) {
                                                if (viewModel.downloadManager.installCached(dlKey, result.appName)) return@IconButton
                                                // MEMEOS: try direct resolution first, fall back to WebView
                                                if (result.source == UpdateSource.MEMEOS) {
                                                    scope.launch {
                                                        val directUrl = viewModel.resolveMemeOsDirectDownload(result.downloadPageUrl)
                                                        if (directUrl != null) {
                                                            viewModel.downloadFromUrl(directUrl, dlKey, result.appName, version = result.versionName)
                                                            return@launch
                                                        }
                                                        // Fall back to WebView
                                                        pendingKey = dlKey
                                                        pendingAppName = result.appName
                                                        pendingVersion = result.versionName
                                                        val intent = Intent(context, com.hyperos.updater.ui.DownloadActivity::class.java)
                                                        intent.putExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_URL, result.downloadPageUrl)
                                                        intent.putExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_APP_NAME, result.appName)
                                                        downloadLauncher.launch(intent)
                                                    }
                                                    return@IconButton
                                                }
                                                // UPTODOWN: open page in WebView — no URL transformation needed
                                                if (result.source == UpdateSource.UPTODOWN || result.source == UpdateSource.APKCOMBO) {
                                                    pendingKey = dlKey
                                                    pendingAppName = result.appName
                                                    pendingVersion = result.versionName
                                                    val intent = Intent(context, com.hyperos.updater.ui.DownloadActivity::class.java)
                                                    intent.putExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_URL, result.downloadPageUrl)
                                                    intent.putExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_APP_NAME, result.appName)
                                                    downloadLauncher.launch(intent)
                                                    return@IconButton
                                                }
                                                pendingKey = dlKey
                                                pendingAppName = result.appName
                                                pendingVersion = result.versionName
                                                val base = result.downloadPageUrl.trimEnd('/')
                                                val slug = base.split("/").last { it.isNotBlank() }
                                                val dlUrl = "$base/${slug.replace("-release", "-android-apk-download")}/"
                                                val intent = Intent(context, com.hyperos.updater.ui.DownloadActivity::class.java)
                                                intent.putExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_URL, dlUrl)
                                                intent.putExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_APP_NAME, result.appName)
                                                downloadLauncher.launch(intent)
                                            } else {
                                                viewModel.downloadFromPage(result)
                                            }
                                        }) {
                                            Icon(Icons.Default.Download, contentDescription = "Download")
                                        }
                                        IconButton(onClick = {
                                            val intent = Intent(context, com.hyperos.updater.ui.screens.detail.AppDetailActivity::class.java)
                                            intent.putExtra(com.hyperos.updater.ui.screens.detail.AppDetailActivity.EXTRA_SEARCH_NAME, result.appName)
                                            result.versionName?.let { intent.putExtra(com.hyperos.updater.ui.screens.detail.AppDetailActivity.EXTRA_SEARCH_VERSION, it) }
                                            intent.putExtra(com.hyperos.updater.ui.screens.detail.AppDetailActivity.EXTRA_SEARCH_SOURCE, result.source.name)
                                            intent.putExtra(com.hyperos.updater.ui.screens.detail.AppDetailActivity.EXTRA_SEARCH_PAGE_URL, result.downloadPageUrl)
                                            result.iconUrl?.let { intent.putExtra(com.hyperos.updater.ui.screens.detail.AppDetailActivity.EXTRA_SEARCH_ICON_URL, it) }
                                            context.startActivity(intent)
                                        }) {
                                            Icon(Icons.Default.Info, contentDescription = "Detalhes", modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }

                            if (dl != null && dl.progress.status.isOngoing()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(progress = { if (dl.progress.totalBytes > 0) dl.progress.bytesDownloaded.toFloat() / dl.progress.totalBytes else 0f }, modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${dl.progress.progress}%", style = MaterialTheme.typography.labelSmall)
                                    Text("${dl.progress.bytesDownloaded.toHumanReadableSize()}", style = MaterialTheme.typography.labelSmall)
                                    Text("${dl.progress.speedBytesPerSec.toHumanReadableSize()}/s", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
