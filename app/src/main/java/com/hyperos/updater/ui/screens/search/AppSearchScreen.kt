package com.hyperos.updater.ui.screens.search

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSearchScreen(
    onBack: () -> Unit,
    viewModel: AppSearchViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val downloads by viewModel.downloadManager.downloads.collectAsState()
    val context = LocalContext.current
    var pendingKey by remember { mutableStateOf("") }

    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val url = result.data?.getStringExtra("downloadUrl")
            if (url != null && pendingKey.isNotBlank()) {
                viewModel.downloadFromUrl(url, pendingKey, pendingKey.removePrefix("APKMIRROR"))
                pendingKey = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find & Install") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
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
                    var expanded by remember { mutableStateOf(false) }
                    val dlKey = result.source.name + result.appName
                    val dl = downloads[dlKey]

                    Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
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

                                if (dl != null) {
                                    when (dl.progress.status) {
                                        DownloadStatus.DOWNLOADING, DownloadStatus.PREPARING, DownloadStatus.INSTALLING ->
                                            IconButton(onClick = { viewModel.downloadManager.cancelDownload(dlKey) }) {
                                                Icon(Icons.Default.Cancel, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
                                            }
                                        DownloadStatus.AWAITING_INSTALL ->
                                            IconButton(onClick = { viewModel.downloadManager.retryInstall(dlKey) }) {
                                                Icon(Icons.Default.InstallMobile, contentDescription = "Install", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        DownloadStatus.COMPLETED ->
                                            Icon(Icons.Default.CheckCircle, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)
                                        DownloadStatus.ERROR, DownloadStatus.CANCELLED ->
                                            IconButton(onClick = { viewModel.downloadManager.dismissDownload(dlKey) }) {
                                                Icon(Icons.Default.Error, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.error)
                                            }
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(onClick = {
                                            if (result.source == UpdateSource.APKMIRROR || result.source == UpdateSource.MEMEOS) {
                                                // Skip WebView if APK already cached
                                                if (viewModel.downloadManager.installCached(dlKey, result.appName)) return@IconButton
                                                pendingKey = dlKey
                                                val base = result.downloadPageUrl.trimEnd('/')
                                                val slug = base.split("/").last { it.isNotBlank() }
                                                val dlUrl = "$base/${slug.replace("-release", "-android-apk-download")}/"
                                                val intent = Intent(context, com.hyperos.updater.ui.DownloadActivity::class.java)
                                                intent.putExtra("url", dlUrl)
                                                downloadLauncher.launch(intent)
                                            } else {
                                                viewModel.downloadFromPage(result)
                                            }
                                        }) {
                                            Icon(Icons.Default.Download, contentDescription = "Download")
                                        }
                                        IconButton(onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadPageUrl))
                                            context.startActivity(intent)
                                        }) {
                                            Icon(Icons.Default.OpenInBrowser, contentDescription = "Source")
                                        }
                                    }
                                }
                            }

                            // Inline progress
                            if (dl != null && dl.progress.status.isOngoing()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { if (dl.progress.totalBytes > 0) dl.progress.bytesDownloaded.toFloat() / dl.progress.totalBytes else 0f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${dl.progress.progress}%", style = MaterialTheme.typography.labelSmall)
                                    Text("${dl.progress.bytesDownloaded.toHumanReadableSize()}", style = MaterialTheme.typography.labelSmall)
                                    Text("${dl.progress.speedBytesPerSec.toHumanReadableSize()}/s", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            if (expanded) {
                                Spacer(modifier = Modifier.height(8.dp)); HorizontalDivider(); Spacer(modifier = Modifier.height(8.dp))
                                Text("App: ${result.appName}", style = MaterialTheme.typography.bodySmall)
                                if (result.versionName != null) Text("Version: ${result.versionName}", style = MaterialTheme.typography.bodySmall)
                                if (!result.devName.isNullOrBlank()) Text("Developer: ${result.devName}", style = MaterialTheme.typography.bodySmall)
                                Text("Source: ${result.source.name}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
