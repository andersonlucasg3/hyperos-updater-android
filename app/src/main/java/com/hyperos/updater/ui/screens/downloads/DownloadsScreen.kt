package com.hyperos.updater.ui.screens.downloads

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hyperos.updater.ui.components.DownloadStatus
import com.hyperos.updater.ui.components.isOngoing
import com.hyperos.updater.util.toHumanReadableSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val downloads by viewModel.downloadManager.downloads.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (downloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No active downloads", style = MaterialTheme.typography.bodyLarge)
                    Text("Downloads will appear here when started", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(downloads.values.toList(), key = { it.key }) { dl ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(dl.appName, style = MaterialTheme.typography.titleMedium)
                                    Text(dl.fileName, style = MaterialTheme.typography.bodySmall)
                                }
                                when (dl.progress.status) {
                                    DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING, DownloadStatus.INSTALLING -> {
                                        IconButton(onClick = { viewModel.downloadManager.cancelDownload(dl.key) }) {
                                            Icon(Icons.Default.Cancel, contentDescription = "Cancel",
                                                tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    DownloadStatus.AWAITING_INSTALL -> {
                                        IconButton(onClick = { viewModel.downloadManager.retryInstall(dl.key) }) {
                                            Icon(Icons.Default.InstallMobile, contentDescription = "Install",
                                                tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    DownloadStatus.COMPLETED -> {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                    DownloadStatus.ERROR, DownloadStatus.CANCELLED -> {
                                        IconButton(onClick = { viewModel.downloadManager.dismissDownload(dl.key) }) {
                                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                                        }
                                    }
                                }
                            }

                            if (dl.progress.status.isOngoing()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { if (dl.progress.totalBytes > 0) dl.progress.bytesDownloaded.toFloat() / dl.progress.totalBytes else 0f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${dl.progress.progress}%", style = MaterialTheme.typography.labelSmall)
                                    Text("${dl.progress.bytesDownloaded.toHumanReadableSize()} / ${dl.progress.totalBytes.toHumanReadableSize()}",
                                        style = MaterialTheme.typography.labelSmall)
                                    Text("${dl.progress.speedBytesPerSec.toHumanReadableSize()}/s", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            if (dl.progress.status == DownloadStatus.INSTALLING) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Installing...", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            }
                            if (dl.progress.status == DownloadStatus.ERROR) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Download failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (dl.progress.status == DownloadStatus.COMPLETED) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Installed", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
