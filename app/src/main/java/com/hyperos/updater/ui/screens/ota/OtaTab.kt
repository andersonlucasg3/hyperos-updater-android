package com.hyperos.updater.ui.screens.ota

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hyperos.updater.domain.model.OtaSource
import com.hyperos.updater.domain.model.UpdateState
import com.hyperos.updater.ui.DownloadActivity
import com.hyperos.updater.ui.components.ShizukuStatusIcon
import com.hyperos.updater.util.toHumanReadableSize

@Composable
fun OtaSourceBadge(source: OtaSource) {
    val (text, color) = when (source) {
        OtaSource.XIAOMI_API -> "Xiaomi API" to Color(0xFFFF6F00)
        OtaSource.MEMEOS -> "MemeOS" to Color(0xFFE53935)
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtaTab(
    viewModel: OtaViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val currentVersion by viewModel.currentVersion.collectAsState()
    val context = LocalContext.current
    var pendingOtaFilename by remember { mutableStateOf("update.zip") }

    // WebView launcher for MemeOs downloads — captures CDN URL for progress tracking
    val otaDownloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val url = result.data?.getStringExtra("downloadUrl")
            if (url != null) {
                val filename = com.hyperos.updater.ui.screens.apps.AppUpdatesViewModel.extractFilename(url)
                viewModel.downloadUpdate(url, filename, null)
            }
        }
    }

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
                    Spacer(modifier = Modifier.height(32.dp))
                    Text("Up to date", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(16.dp))
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
                            if (s.update.fileSize > 0) Text("Size: ${s.update.fileSize.toHumanReadableSize()}", style = MaterialTheme.typography.bodyMedium)
                            s.update.branch?.takeIf { it.isNotBlank() }?.let { Text("Branch: $it", style = MaterialTheme.typography.bodySmall) }
                            s.update.publishedDate?.let { Text("Published: $it", style = MaterialTheme.typography.bodySmall) }

                            // Source badges
                            if (s.update.otaSources.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Sources:", style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    s.update.otaSources.forEach { src ->
                                        OtaSourceBadge(src.source)
                                    }
                                }
                                s.update.otaSources.forEach { src ->
                                    Text("  ${src.source.name}: ${src.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            s.update.changelog?.takeIf { it.isNotBlank() }?.let {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Changelog:", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(modifier = Modifier.height(16.dp))

                            // Download button — direct for Xiaomi API, WebView for MemeOs
                            if (s.update.source == OtaSource.MEMEOS) {
                                // MemeOs: open WebView, capture CDN URL, then show download progress
                                Button(onClick = {
                                    val intent = Intent(context, DownloadActivity::class.java)
                                    intent.putExtra("url", s.update.downloadUrl ?: "https://memeosupdates.com/hyperos/")
                                    otaDownloadLauncher.launch(intent)
                                }) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download from MemeOS")
                                }
                            } else if (s.update.downloadUrl != null) {
                                Button(onClick = {
                                    viewModel.downloadUpdate(s.update.downloadUrl, s.update.filename ?: "update.zip", s.update.md5)
                                }) {
                                    Text(if (s.update.fileSize > 0) "Download (${s.update.fileSize.toHumanReadableSize()})" else "Download")
                                }
                            } else {
                                Text("No download URL available", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
