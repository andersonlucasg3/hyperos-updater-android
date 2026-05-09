package com.hyperos.updater.ui.screens.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.ui.components.SourceBadge
import com.hyperos.updater.util.toHumanReadableSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    packageName: String,
    onBack: () -> Unit,
    viewModel: AppDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(packageName) {
        viewModel.load(packageName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.appUpdate?.appName ?: "App Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            state.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }
            }
            state.appUpdate != null -> {
                val update = state.appUpdate!!
                val hasUpdate = update.currentVersion != update.latestVersion

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // App info card
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(update.appName, style = MaterialTheme.typography.headlineMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    update.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SourceBadge(update.updateSource)
                                }
                            }
                        }
                    }

                    // Version info
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Version", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Installed", style = MaterialTheme.typography.labelSmall)
                                        Text(update.currentVersion, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    if (hasUpdate) {
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("Available", style = MaterialTheme.typography.labelSmall)
                                            Text(
                                                update.latestVersion,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                                if (!hasUpdate) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Up to date", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    // Download/Install
                    if (hasUpdate) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Action", style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.height(8.dp))

                                    state.downloadProgress?.let { progress ->
                                        LinearProgressIndicator(
                                            progress = { progress / 100f },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("Downloading: $progress%", style = MaterialTheme.typography.bodySmall)
                                    } ?: Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { viewModel.downloadAndInstall() },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Install")
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.openSourcePage() }
                                        ) {
                                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Source")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Changelog
                    val changelog = state.appUpdate?.changelog
                    if (!changelog.isNullOrBlank()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Changelog", style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(changelog, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    // Update History
                    if (state.history.isNotEmpty()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Update History", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        items(state.history) { entry ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("${entry.versionFrom} → ${entry.versionTo}", style = MaterialTheme.typography.bodyMedium)
                                        Text(entry.installMethod, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(
                                        formatDate(entry.installedAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDate(epoch: Long): String {
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epoch))
    } catch (_: Exception) { "" }
}
