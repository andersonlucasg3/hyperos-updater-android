package com.hyperos.updater.ui.screens.home

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import com.hyperos.updater.domain.model.AppUpdate
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.hyperos.updater.domain.model.UpdateState
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.ui.components.AppListItem
import com.hyperos.updater.ui.components.DownloadStatus
import com.hyperos.updater.ui.components.SourceBadge
import com.hyperos.updater.ui.components.isOngoing
import com.hyperos.updater.ui.navigation.Screen
import com.hyperos.updater.ui.screens.apps.AppUpdatesViewModel
import com.hyperos.updater.ui.screens.search.AppSearchViewModel
import com.hyperos.updater.util.toHumanReadableSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    homeViewModel: HomeViewModel = hiltViewModel(),
    appViewModel: AppUpdatesViewModel = hiltViewModel(),
    searchViewModel: AppSearchViewModel = hiltViewModel()
) {
    val otaState by homeViewModel.otaState.collectAsState()
    val deviceVersion by homeViewModel.currentVersion.collectAsState()
    val searchState by searchViewModel.state.collectAsState()
    val appCache by appViewModel.cache.collectAsState()
    val isScanning by appViewModel.isScanning.collectAsState()
    val downloadState by appViewModel.downloadManager.downloads.collectAsState()
    var filterText by remember { mutableStateOf("") }
    val context = LocalContext.current
    var pendingKey by remember { mutableStateOf("") }

    // Derive sorted list from cache — only recomputes when cache changes, not on download ticks
    val sortedUpdates by remember {
        derivedStateOf {
            appCache.values.toList().sortedWith(
                compareByDescending<AppUpdate> { it.updateSource != UpdateSource.UNTRACKED && it.currentVersion != it.latestVersion }
                    .thenBy { it.appName.lowercase() }
            )
        }
    }

    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val url = result.data?.getStringExtra("downloadUrl") ?: return@rememberLauncherForActivityResult
            // Check app updates pending download first
            val appKey = appViewModel.pendingDownloadKey.value
            if (appKey != null) {
                appViewModel.onDownloadUrlCaptured(url)
            } else if (pendingKey.isNotBlank()) {
                searchViewModel.downloadFromUrl(url, pendingKey, pendingKey.removePrefix("APKMIRROR"))
                pendingKey = ""
            }
        }
    }

    LaunchedEffect(Unit) {
        homeViewModel.checkOta()
        appViewModel.checkAllApps()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HyperOS Updater") },
                actions = {
                    val dlCount = downloadState.count { it.value.progress.status.isOngoing() }
                    IconButton(onClick = { navController.navigate(Screen.Downloads.route) }) {
                        BadgedBox(badge = { if (dlCount > 0) Badge { Text("$dlCount") } }) {
                            Icon(Icons.Default.Download, contentDescription = "Downloads")
                        }
                    }
                    val shizukuReady = com.hyperos.updater.util.ShizukuHelper.isReady()
                    Icon(
                        if (shizukuReady) Icons.Default.Security else Icons.Default.Warning,
                        contentDescription = if (shizukuReady) "Shizuku ready" else "Shizuku not configured",
                        tint = if (shizukuReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = {
                        appViewModel.checkAllApps()
                        homeViewModel.checkOta()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
            // --- OTA Section ---
            item {
                Text("System Update", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = when (otaState) {
                        is UpdateState.Available -> MaterialTheme.colorScheme.primaryContainer
                        is UpdateState.Error -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    })
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Current: $deviceVersion", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))

                        when (val s = otaState) {
                            is UpdateState.Idle -> {
                                Text("Up to date", style = MaterialTheme.typography.bodyLarge)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(onClick = { homeViewModel.checkOta() }) { Text("Check for Updates") }
                            }
                            is UpdateState.Checking -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Checking...")
                                }
                            }
                            is UpdateState.Available -> {
                                Text("Update Available", style = MaterialTheme.typography.titleMedium)
                                Text(s.update.version, style = MaterialTheme.typography.bodyLarge)
                                Text(s.update.fileSize.toHumanReadableSize(), style = MaterialTheme.typography.bodyMedium)
                                s.update.changelog?.let { changelog ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(changelog, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    s.update.downloadUrl?.let { url ->
                                        Button(onClick = {
                                            homeViewModel.downloadOta(url, s.update.filename ?: "update.zip", s.update.md5)
                                        }) { Text("Download (${s.update.fileSize.toHumanReadableSize()})") }
                                    }
                                    OutlinedButton(onClick = { homeViewModel.checkOta() }) { Text("Check Again") }
                                }
                            }
                            is UpdateState.Downloading -> {
                                LinearProgressIndicator(progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("${s.progress}% - ${s.bytesDownloaded.toHumanReadableSize()} / ${s.totalBytes.toHumanReadableSize()}")
                            }
                            is UpdateState.ReadyToInstall -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download complete")
                                }
                            }
                            is UpdateState.Installing -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Installing...")
                                }
                            }
                            is UpdateState.Installed -> Text("Installed successfully")
                            is UpdateState.Error -> {
                                Text(s.message, color = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedButton(onClick = { homeViewModel.checkOta() }) { Text("Retry") }
                            }
                        }
                    }
                }
            }

            // --- Find & Install Section ---
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Find & Install", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchState.query,
                    onValueChange = { searchViewModel.search(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search apps by name...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true
                )
                if (searchState.isSearching) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // Search results
            if (searchState.results.isNotEmpty()) {
                items(searchState.results, key = { it.source.name + it.downloadPageUrl }) { result ->
                    var expanded by remember { mutableStateOf(false) }
                    val dlKey = result.source.name + result.appName
                    val dl = downloadState[dlKey]

                    Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(result.appName, style = MaterialTheme.typography.titleMedium)
                                    if (result.versionName != null) Text("v${result.versionName}", style = MaterialTheme.typography.bodySmall)
                                    Row {
                                        SourceBadge(result.source)
                                        if (!result.devName.isNullOrBlank()) { Spacer(modifier = Modifier.width(4.dp)); Text(result.devName, style = MaterialTheme.typography.labelSmall) }
                                    }
                                }
                                if (dl != null) {
                                    when {
                                        dl.progress.status.isOngoing() -> IconButton(onClick = { appViewModel.downloadManager.cancelDownload(dlKey) }) {
                                            Icon(Icons.Default.Cancel, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error) }
                                        dl.progress.status == DownloadStatus.COMPLETED -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        else -> IconButton(onClick = { appViewModel.downloadManager.dismissDownload(dlKey) }) {
                                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(onClick = {
                                            if (result.source == UpdateSource.APKMIRROR) {
                                                pendingKey = dlKey
                                                val base = result.downloadPageUrl.trimEnd('/')
                                                val slug = base.split("/").last { it.isNotBlank() }
                                                val dlUrl = "$base/${slug.replace("-release", "-android-apk-download")}/"
                                                val intent = Intent(context, com.hyperos.updater.ui.DownloadActivity::class.java)
                                                intent.putExtra("url", dlUrl)
                                                downloadLauncher.launch(intent)
                                            } else { searchViewModel.downloadFromPage(result) }
                                        }) { Icon(Icons.Default.Download, contentDescription = "Download") }
                                        IconButton(onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadPageUrl))
                                            context.startActivity(intent)
                                        }) { Icon(Icons.Default.OpenInBrowser, contentDescription = "Source") }
                                    }
                                }
                            }
                            // Progress bar
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
                            if (expanded) { Spacer(modifier = Modifier.height(8.dp)); HorizontalDivider(); Spacer(modifier = Modifier.height(8.dp))
                                Text("App: ${result.appName}", style = MaterialTheme.typography.bodySmall)
                                if (result.versionName != null) Text("Version: ${result.versionName}", style = MaterialTheme.typography.bodySmall)
                                if (!result.devName.isNullOrBlank()) Text("Developer: ${result.devName}", style = MaterialTheme.typography.bodySmall)
                                Text("Source: ${result.source.name}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            if (!searchState.isSearching && searchState.query.isNotBlank() && searchState.results.isEmpty()) {
                item { Text("No results found", style = MaterialTheme.typography.bodyLarge) }
            }

            // --- App Updates Section ---
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("App Updates", style = MaterialTheme.typography.titleLarge)
                    if (isScanning) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else {
                        val updateCount = sortedUpdates.count { it.updateSource != UpdateSource.UNTRACKED && it.currentVersion != it.latestVersion }
                        if (updateCount > 0) AssistChip(onClick = {}, label = { Text("$updateCount updates") })
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = filterText, onValueChange = { filterText = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Filter apps by name...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = { if (filterText.isNotEmpty()) IconButton(onClick = { filterText = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear") } },
                    singleLine = true
                )
            }

            val filteredApps = sortedUpdates.filter { filterText.isBlank() || it.appName.contains(filterText, ignoreCase = true) || it.packageName.contains(filterText, ignoreCase = true) }

            if (isScanning && filteredApps.isEmpty()) {
                item { Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp)); Text("Scanning installed apps...") } }
            } else if (filteredApps.isEmpty()) {
                item { Card(modifier = Modifier.fillMaxWidth()) { Text(if (filterText.isNotBlank()) "No apps matching \"$filterText\"" else "No apps found", modifier = Modifier.padding(16.dp)) } }
            } else {
                items(filteredApps, key = { it.packageName + it.appType.name }) { update ->
                    val dlKey = update.updateSource.name + update.appName
                    val dl = downloadState[dlKey]
                    var expanded by remember { mutableStateOf(false) }
                    val hasUpdate = update.currentVersion != update.latestVersion

                    Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(update.appName, style = MaterialTheme.typography.titleMedium)
                                    Text(if (hasUpdate) "${update.currentVersion} → ${update.latestVersion}" else update.currentVersion, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        SourceBadge(update.updateSource)
                                        if (update.appType == com.hyperos.updater.domain.model.AppType.SYSTEM) SourceBadge(UpdateSource.TRACKER)
                                    }
                                }
                                if (dl != null) {
                                    when {
                                        dl.progress.status.isOngoing() -> IconButton(onClick = { appViewModel.downloadManager.cancelDownload(dlKey) }) {
                                            Icon(Icons.Default.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                                        dl.progress.status == DownloadStatus.COMPLETED -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        else -> IconButton(onClick = { appViewModel.downloadManager.dismissDownload(dlKey) }) {
                                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (hasUpdate) IconButton(onClick = {
                                            val key = update.updateSource.name + update.appName
                                            appViewModel.setPendingDownloadKey(key)
                                            val dlPageUrl = appViewModel.getDownloadPageUrl(update)
                                            val intent = Intent(context, com.hyperos.updater.ui.DownloadActivity::class.java)
                                            intent.putExtra("url", dlPageUrl)
                                            downloadLauncher.launch(intent)
                                        }) {
                                            Icon(Icons.Default.Download, contentDescription = "Install") }
                                        if (update.downloadUrl != null) IconButton(onClick = { appViewModel.openSourcePage(update) }) {
                                            Icon(Icons.Default.OpenInBrowser, contentDescription = "Source", modifier = Modifier.size(20.dp)) }
                                    }
                                }
                            }
                            if (dl != null && dl.progress.status.isOngoing()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(progress = { if (dl.progress.totalBytes > 0) dl.progress.bytesDownloaded.toFloat() / dl.progress.totalBytes else 0f }, modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${dl.progress.progress}%", style = MaterialTheme.typography.labelSmall)
                                    Text(dl.progress.bytesDownloaded.toHumanReadableSize(), style = MaterialTheme.typography.labelSmall)
                                    Text("${dl.progress.speedBytesPerSec.toHumanReadableSize()}/s", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (expanded) { Spacer(modifier = Modifier.height(8.dp)); HorizontalDivider(); Spacer(modifier = Modifier.height(8.dp))
                                Text("Package: ${update.packageName}", style = MaterialTheme.typography.bodySmall)
                                Text("Current: ${update.currentVersion}", style = MaterialTheme.typography.bodySmall)
                                if (hasUpdate) Text("Latest: ${update.latestVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
