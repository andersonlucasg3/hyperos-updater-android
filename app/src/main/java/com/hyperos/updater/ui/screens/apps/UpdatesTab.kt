package com.hyperos.updater.ui.screens.apps

import android.content.Intent
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
import com.hyperos.updater.domain.model.AppUpdate
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.ui.components.DownloadStatus
import com.hyperos.updater.ui.components.ShizukuStatusIcon
import com.hyperos.updater.ui.components.SourceBadge
import com.hyperos.updater.ui.components.isOngoing
import com.hyperos.updater.util.toHumanReadableSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesTab(
    viewModel: AppUpdatesViewModel = hiltViewModel()
) {
    val appList = viewModel.appList
    val ignored = viewModel.ignoredPackages
    val isScanning by viewModel.isScanning.collectAsState()
    val downloadState by viewModel.downloadManager.downloads.collectAsState()
    var filterText by remember { mutableStateOf("") }
    var showOnlyUpdates by remember { mutableStateOf(true) }
    val context = LocalContext.current

    val displayList by remember {
        derivedStateOf {
            appList.sortedWith(
                compareByDescending<AppUpdate> { it.updateSource != UpdateSource.UNTRACKED && it.currentVersion != it.latestVersion }
                    .thenBy { it.appName.lowercase() }
            ).filter { update ->
                update.packageName !in ignored &&
                (filterText.isBlank() || update.appName.contains(filterText, ignoreCase = true) || update.packageName.contains(filterText, ignoreCase = true)) &&
                (!showOnlyUpdates || (update.updateSource != UpdateSource.UNTRACKED && update.currentVersion != update.latestVersion))
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.checkAllApps() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Updates") },
                actions = {
                    ShizukuStatusIcon(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    val updateCount = displayList.count { it.updateSource != UpdateSource.UNTRACKED && it.currentVersion != it.latestVersion }
                    if (isScanning) CircularProgressIndicator(modifier = Modifier.size(14.dp))
                    if (updateCount > 0) AssistChip(onClick = {}, label = { Text("$updateCount updates") })
                    FilterChip(selected = showOnlyUpdates, onClick = { showOnlyUpdates = !showOnlyUpdates },
                        label = { Text("Updatable") },
                        leadingIcon = if (showOnlyUpdates) {{ Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(16.dp)) }} else null)
                }
            }

            item {
                OutlinedTextField(value = filterText, onValueChange = { filterText = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Filter apps by name...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = { if (filterText.isNotEmpty()) IconButton(onClick = { filterText = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear") } },
                    singleLine = true)
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
                                            viewModel.setPendingDownloadKey(key)
                                            val dlPageUrl = viewModel.getDownloadPageUrl(update)
                                            val intent = Intent(context, com.hyperos.updater.ui.DownloadActivity::class.java)
                                            intent.putExtra("url", dlPageUrl)
                                            // Launch needs to be handled via ActivityResultLauncher — use startActivity for now
                                            context.startActivity(intent)
                                        }) {
                                            Icon(Icons.Default.Download, contentDescription = "Install") }
                                        if (update.downloadUrl != null) IconButton(onClick = { viewModel.openSourcePage(update) }) {
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
                            if (expanded) {
                                Spacer(modifier = Modifier.height(8.dp)); HorizontalDivider(); Spacer(modifier = Modifier.height(8.dp))
                                Text("Package: ${update.packageName}", style = MaterialTheme.typography.bodySmall)
                                Text("Installed: ${update.currentVersion}", style = MaterialTheme.typography.bodySmall)
                                update.sourceVersions.forEach { sv ->
                                    val svKey = sv.source.name + update.appName
                                    val svDl = downloadState[svKey]
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                        SourceBadge(sv.source)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(sv.version, style = MaterialTheme.typography.bodySmall,
                                            color = if (sv.version != update.currentVersion) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f))
                                        if (sv.downloadUrl != null && sv.version != update.currentVersion) {
                                            if (svDl != null && svDl.progress.status.isOngoing()) {
                                                Text("${svDl.progress.progress}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                            } else if (svDl?.progress?.status == DownloadStatus.COMPLETED) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                            } else {
                                                IconButton(onClick = {
                                                    val url = viewModel.getSourcePageUrl(update.packageName, sv.source, sv.downloadUrl)
                                                    val needsWebView = sv.source == UpdateSource.APKMIRROR || sv.source == UpdateSource.APKCOMBO || sv.source == UpdateSource.MEMEOS || sv.source == UpdateSource.APKPURE
                                                    if (needsWebView) {
                                                        viewModel.setPendingDownloadKey(svKey)
                                                        val intent = Intent(context, com.hyperos.updater.ui.DownloadActivity::class.java)
                                                        intent.putExtra("url", url)
                                                        context.startActivity(intent)
                                                    } else {
                                                        val filename = url.split("/").lastOrNull()?.substringBefore("?")?.takeIf { it.isNotBlank() } ?: "${update.packageName}.apk"
                                                        viewModel.downloadManager.startDownload(url, filename, svKey, update.appName)
                                                    }
                                                }, modifier = Modifier.size(32.dp)) {
                                                    Icon(Icons.Default.Download, contentDescription = "Download from ${sv.source.name}", modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                TextButton(onClick = { viewModel.ignoredPackages.add(update.packageName) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                    Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Hide this app", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
