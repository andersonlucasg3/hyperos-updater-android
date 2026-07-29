package com.hyperos.updater.ui.screens.apps

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hyperos.updater.domain.model.AppUpdate
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.ui.components.DownloadStatus
import com.hyperos.updater.ui.components.SourceBadge
import com.hyperos.updater.ui.components.isOngoing
import com.hyperos.updater.util.toHumanReadableSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesTab(
    viewModel: AppUpdatesViewModel = hiltViewModel()
) {
    val appList = viewModel.appList
    val blacklisted by viewModel.blacklistedPackages.collectAsState()
    val skipped by viewModel.skippedVersions.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scan by viewModel.scanProgress.collectAsState()
    val downloadState by viewModel.downloadManager.downloads.collectAsState()
    val checkingApps by viewModel.checkingApps.collectAsState()
    var filterText by remember { mutableStateOf("") }
    val showOnlyUpdates by viewModel.updatableFilter.collectAsState()
    val showSystemApps by viewModel.showSystemApps.collectAsState()
    val context = LocalContext.current
    var pendingDlKey by remember { mutableStateOf("") }
    var pendingDlAppName by remember { mutableStateOf("") }
    var pendingDlVersion by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // WebView download launcher — captures CDN URL + replay headers for native OkHttp download
    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val url = result.data?.getStringExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_DOWNLOAD_URL)
            if (url != null && pendingDlKey.isNotBlank()) {
                val referer = result.data?.getStringExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_REFERER) ?: ""
                val ua = result.data?.getStringExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_USER_AGENT) ?: ""
                val cookie = result.data?.getStringExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_COOKIE) ?: ""
                val headers = buildMap {
                    if (referer.isNotBlank()) put("Referer", referer)
                    if (ua.isNotBlank()) put("User-Agent", ua)
                    if (cookie.isNotBlank()) put("Cookie", cookie)
                }
                val filename = AppUpdatesViewModel.buildApkFileName(url, pendingDlAppName, pendingDlVersion)
                viewModel.downloadManager.startDownload(url, filename, pendingDlKey, pendingDlAppName, headers)
            }
        }
        pendingDlKey = ""
        pendingDlAppName = ""
        pendingDlVersion = null
    }

    val displayList by remember {
        derivedStateOf {
            appList.sortedWith(
                compareByDescending<AppUpdate> { it.updateSource != UpdateSource.UNTRACKED && it.currentVersion != it.latestVersion }
                    .thenBy { it.appName.lowercase() }
            ).filter { update ->
                update.packageName !in blacklisted &&
                "${update.packageName}|${update.latestVersion}" !in skipped &&
                (showSystemApps || update.appType != com.hyperos.updater.domain.model.AppType.SYSTEM) &&
                (filterText.isBlank() || update.appName.contains(filterText, ignoreCase = true) || update.packageName.contains(filterText, ignoreCase = true)) &&
                (!showOnlyUpdates || (update.updateSource != UpdateSource.UNTRACKED && update.currentVersion != update.latestVersion))
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.checkAllAppsIfNeeded() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Updates") },
                actions = {
                    IconButton(onClick = { viewModel.checkAllApps() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    val updateCount = displayList.count { it.updateSource != UpdateSource.UNTRACKED && it.currentVersion != it.latestVersion }
                    if (isScanning) CircularProgressIndicator(modifier = Modifier.size(14.dp))
                    if (updateCount > 0) AssistChip(onClick = {}, label = { Text("$updateCount updates") })
                    FilterChip(selected = showOnlyUpdates, onClick = { viewModel.setUpdatableFilter(!showOnlyUpdates) },
                        label = { Text("Updatable") },
                        leadingIcon = if (showOnlyUpdates) {{ Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(16.dp)) }} else null)
                    FilterChip(selected = showSystemApps, onClick = { viewModel.setShowSystemApps(!showSystemApps) },
                        label = { Text("Sistema") },
                        leadingIcon = if (showSystemApps) {{ Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(16.dp)) }} else null)
                }
            }

            item {
                OutlinedTextField(value = filterText, onValueChange = { filterText = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Filter apps by name...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = { if (filterText.isNotEmpty()) IconButton(onClick = { filterText = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear") } },
                    singleLine = true)
            }

            if (isScanning) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (scan != null && scan!!.second > 0) {
                            LinearProgressIndicator(
                                progress = { scan!!.first.toFloat() / scan!!.second },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("${scan!!.first} de ${scan!!.second}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (isScanning && displayList.isEmpty()) {
                item { Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp)); Text("Scanning installed apps...") }}
            } else if (displayList.isEmpty()) {
                item { Card(modifier = Modifier.fillMaxWidth()) { Text(if (filterText.isNotBlank()) "No apps matching \"$filterText\"" else "No apps found", modifier = Modifier.padding(16.dp)) } }
            } else {
                items(displayList, key = { it.packageName + it.appType.name }) { update ->
                    val dlKey = update.updateSource.name + update.appName
                    val dl = downloadState[dlKey]
                    val hasUpdate = update.currentVersion != update.latestVersion
                    val recheckKey = update.packageName + update.appType.name
                    val isRechecking = recheckKey in checkingApps

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).clickable {
                                    val intent = Intent(context, com.hyperos.updater.ui.screens.detail.AppDetailActivity::class.java)
                                    intent.putExtra(com.hyperos.updater.ui.screens.detail.AppDetailActivity.EXTRA_PACKAGE_NAME, update.packageName)
                                    intent.putExtra(com.hyperos.updater.ui.screens.detail.AppDetailActivity.EXTRA_APP_TYPE, update.appType.name)
                                    context.startActivity(intent)
                                }) {
                                    com.hyperos.updater.ui.components.PackageAppIcon(update.packageName)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(update.appName, style = MaterialTheme.typography.titleMedium)
                                        Text(if (hasUpdate) "${update.currentVersion} → ${update.latestVersion}" else update.currentVersion, style = MaterialTheme.typography.bodySmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            SourceBadge(update.updateSource)
                                            if (update.appType == com.hyperos.updater.domain.model.AppType.SYSTEM) SourceBadge(UpdateSource.TRACKER)
                                        }
                                    }
                                }
                                if (dl != null) {
                                    when {
                                        dl.progress.status == DownloadStatus.INSTALLING ->
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(2.dp))
                                        dl.progress.status.isOngoing() -> IconButton(onClick = { viewModel.downloadManager.cancelDownload(dlKey) }) {
                                            Icon(Icons.Default.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                                        dl.progress.status == DownloadStatus.COMPLETED -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        dl.progress.status == DownloadStatus.AWAITING_INSTALL -> IconButton(onClick = { viewModel.downloadManager.retryInstall(dlKey) }) {
                                            Icon(Icons.Default.InstallMobile, contentDescription = "Install", tint = MaterialTheme.colorScheme.primary) }
                                        else -> IconButton(onClick = { viewModel.downloadManager.dismissDownload(dlKey) }) {
                                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (hasUpdate) IconButton(onClick = {
                                            val key = update.updateSource.name + update.appName
                                            if (viewModel.downloadManager.installCached(key, update.appName)) return@IconButton
                                            // MEMEOS: try direct resolution first, fall back to WebView
                                            if (update.updateSource == UpdateSource.MEMEOS) {
                                                scope.launch {
                                                    val versionPage = update.downloadUrl ?: viewModel.getDownloadPageUrl(update)
                                                    val directUrl = viewModel.resolveMemeOsDirectDownload(versionPage)
                                                    if (directUrl != null) {
                                                        val filename = AppUpdatesViewModel.buildApkFileName(directUrl, update.appName, update.latestVersion)
                                                        viewModel.downloadManager.startDownload(directUrl, filename, key, update.appName)
                                                        return@launch
                                                    }
                                                    // Fall back to WebView
                                                    pendingDlKey = key
                                                    pendingDlAppName = update.appName
                                                    pendingDlVersion = update.latestVersion
                                                    val intent = Intent(context, com.hyperos.updater.ui.DownloadActivity::class.java)
                                                    intent.putExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_URL, viewModel.getDownloadPageUrl(update))
                                                    intent.putExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_APP_NAME, update.appName)
                                                    downloadLauncher.launch(intent)
                                                }
                                                return@IconButton
                                            }
                                            // Sources with direct APK URLs: download natively, no WebView
                                            val hasDirectUrl = update.updateSource == UpdateSource.APTOIDE ||
                                                update.updateSource == UpdateSource.GITHUB ||
                                                update.updateSource == UpdateSource.FDROID ||
                                                update.updateSource == UpdateSource.TENCENT
                                            if (hasDirectUrl && update.downloadUrl != null) {
                                                val filename = AppUpdatesViewModel.buildApkFileName(update.downloadUrl!!, update.appName, update.latestVersion)
                                                viewModel.downloadManager.startDownload(update.downloadUrl!!, filename, key, update.appName)
                                                return@IconButton
                                            }
                                            pendingDlKey = key
                                            pendingDlAppName = update.appName
                                            pendingDlVersion = update.latestVersion
                                            val dlPageUrl = viewModel.getDownloadPageUrl(update)
                                            val intent = Intent(context, com.hyperos.updater.ui.DownloadActivity::class.java)
                                            intent.putExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_URL, dlPageUrl)
                                            intent.putExtra(com.hyperos.updater.ui.DownloadActivity.EXTRA_APP_NAME, update.appName)
                                            downloadLauncher.launch(intent)
                                        }) {
                                            Icon(Icons.Default.Download, contentDescription = "Install") }
                                        IconButton(onClick = {
                                            val intent = Intent(context, com.hyperos.updater.ui.screens.detail.AppDetailActivity::class.java)
                                            intent.putExtra(com.hyperos.updater.ui.screens.detail.AppDetailActivity.EXTRA_PACKAGE_NAME, update.packageName)
                                            intent.putExtra(com.hyperos.updater.ui.screens.detail.AppDetailActivity.EXTRA_APP_TYPE, update.appType.name)
                                            context.startActivity(intent)
                                        }) {
                                            Icon(Icons.Default.Info, contentDescription = "Detalhes", modifier = Modifier.size(20.dp))
                                        }
                                        if (isRechecking) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                        } else {
                                            IconButton(onClick = { viewModel.recheckApp(update) }) {
                                                Icon(Icons.Default.Refresh, contentDescription = "Recheck app")
                                            }
                                        }
                                    }
                                }
                            }
                            if (dl != null && dl.progress.status.isOngoing()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                if (dl.progress.status == DownloadStatus.INSTALLING) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Instalando...", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                } else {
                                    LinearProgressIndicator(progress = { if (dl.progress.totalBytes > 0) dl.progress.bytesDownloaded.toFloat() / dl.progress.totalBytes else 0f }, modifier = Modifier.fillMaxWidth())
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${dl.progress.progress}%", style = MaterialTheme.typography.labelSmall)
                                        Text(dl.progress.bytesDownloaded.toHumanReadableSize(), style = MaterialTheme.typography.labelSmall)
                                        Text("${dl.progress.speedBytesPerSec.toHumanReadableSize()}/s", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            if (dl?.progress?.status == DownloadStatus.ERROR && dl.progress.errorMessage != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Erro: ${dl.progress.errorMessage}", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
