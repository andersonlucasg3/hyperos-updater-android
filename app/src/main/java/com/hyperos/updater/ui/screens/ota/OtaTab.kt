package com.hyperos.updater.ui.screens.ota

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hyperos.updater.domain.model.UpdateState
import com.hyperos.updater.ui.components.ShizukuStatusIcon
import com.hyperos.updater.util.toHumanReadableSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtaTab(
    viewModel: OtaViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val currentVersion by viewModel.currentVersion.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OS Updates") },
                actions = {
                    ShizukuStatusIcon(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = { viewModel.checkForUpdates() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Current Version", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(currentVersion.ifBlank { "Unknown" }, style = MaterialTheme.typography.headlineMedium)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            when (val s = state) {
                is UpdateState.Idle -> {
                    Button(onClick = { viewModel.checkForUpdates() }) {
                        Text("Check for Updates")
                    }
                }
                is UpdateState.Checking -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Checking for updates...", style = MaterialTheme.typography.bodyLarge)
                }
                is UpdateState.Available -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Update Available", style = MaterialTheme.typography.titleLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Version: ${s.update.version}", style = MaterialTheme.typography.bodyLarge)
                            Text("Size: ${s.update.fileSize.toHumanReadableSize()}", style = MaterialTheme.typography.bodyMedium)
                            s.update.branch?.let { Text("Branch: $it", style = MaterialTheme.typography.bodySmall) }
                            s.update.publishedDate?.let { Text("Published: $it", style = MaterialTheme.typography.bodySmall) }
                            s.update.changelog?.let {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Changelog:", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            s.update.downloadUrl?.let { url ->
                                Button(onClick = {
                                    viewModel.downloadUpdate(url, s.update.filename ?: "update.zip", s.update.md5)
                                }) {
                                    Text("Download (${s.update.fileSize.toHumanReadableSize()})")
                                }
                            }
                        }
                    }
                }
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${s.progress}%", style = MaterialTheme.typography.titleLarge)
                    Text("${s.bytesDownloaded.toHumanReadableSize()} / ${s.totalBytes.toHumanReadableSize()}", style = MaterialTheme.typography.bodyMedium)
                }
                is UpdateState.ReadyToInstall -> {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Download Complete", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("File saved to Downloads/HyperOSUpdater", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.retryDownload() }) {
                        Text("Re-download")
                    }
                }
                is UpdateState.Installing -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Installing...", style = MaterialTheme.typography.bodyLarge)
                }
                is UpdateState.Installed -> {
                    Text("Update Installed", style = MaterialTheme.typography.titleLarge)
                }
                is UpdateState.Error -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(s.message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.checkForUpdates() }) { Text("Retry Check") }
                        OutlinedButton(onClick = { viewModel.retryDownload() }) { Text("Retry Download") }
                    }
                }
            }
        }
    }
}
