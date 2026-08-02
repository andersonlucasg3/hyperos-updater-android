package com.hyperos.updater.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hyperos.updater.BuildConfig
import com.hyperos.updater.data.remote.SelfUpdateRelease
import com.hyperos.updater.ui.components.DownloadStatus
import com.hyperos.updater.ui.screens.apps.AppUpdatesViewModel
import com.hyperos.updater.util.toHumanReadableSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val autoUpdateEnabled by viewModel.autoUpdateEnabled.collectAsState()
    val rootAvailable by viewModel.rootAvailable.collectAsState()
    val rootDiagnosis by viewModel.rootDiagnosis.collectAsState()
    val blacklisted by viewModel.blacklistedPackages.collectAsState()
    val skipped by viewModel.skippedVersions.collectAsState()
    val selfUpdateState by viewModel.selfUpdateState.collectAsState()
    val selfUpdateDownload by viewModel.selfUpdateDownload.collectAsState()
    val isGeneratingLogs by viewModel.isGeneratingLogs.collectAsState()
    val logShareError by viewModel.logShareError.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            // Root Section
            Text("Root", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (rootAvailable) {
                                true -> Icons.Default.Security
                                false -> Icons.Default.Warning
                                null -> Icons.Default.HourglassEmpty
                            },
                            contentDescription = null,
                            tint = when (rootAvailable) {
                                true -> MaterialTheme.colorScheme.primary
                                false -> MaterialTheme.colorScheme.error
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when (rootAvailable) {
                                true -> "Root disponível"
                                false -> "Root não disponível"
                                null -> "Verificando..."
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.requestRootAccess() }) {
                            Text("Solicitar acesso root")
                        }
                        OutlinedButton(onClick = { viewModel.refreshRootStatus() }) {
                            Text("Verificar novamente")
                        }
                    }
                    if (!rootDiagnosis.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            rootDiagnosis!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Auto Update Section
            Text("App Updates", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Atualização automática", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Baixa e instala atualizações automaticamente via root quando disponíveis",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoUpdateEnabled,
                        onCheckedChange = { viewModel.setAutoUpdateEnabled(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Self-update Section
            Text("Atualização do app", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Versão atual: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.BUILD_TIME})",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Button morphs into "Baixar atualização" when a release is available
                    val availableRelease = (selfUpdateState as? SelfUpdateState.Available)?.release
                    val showDownloadAction = availableRelease != null && selfUpdateDownload == null

                    Button(
                        onClick = {
                            if (showDownloadAction && availableRelease != null) {
                                val fileName = AppUpdatesViewModel.buildApkFileName(
                                    availableRelease.apkUrl!!, "HyperOS-Updater", availableRelease.version
                                )
                                viewModel.downloadManager.startDownload(
                                    availableRelease.apkUrl, fileName, "SELFUPDATE", "HyperOS Updater"
                                )
                            } else {
                                viewModel.checkSelfUpdate()
                            }
                        },
                        enabled = selfUpdateState !is SelfUpdateState.Checking
                    ) {
                        if (selfUpdateState is SelfUpdateState.Checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else if (showDownloadAction) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (showDownloadAction) "Baixar atualização" else "Verificar atualização")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Per-state content
                    when (val state = selfUpdateState) {
                        is SelfUpdateState.Idle -> { /* nothing yet */ }
                        is SelfUpdateState.Checking -> {
                            Text("Verificando…", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is SelfUpdateState.UpToDate -> {
                            Text("App atualizado ✓", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        is SelfUpdateState.NoRelease -> {
                            Text("Nenhuma release publicada ainda",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is SelfUpdateState.Error -> {
                            Text(state.message, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(onClick = { viewModel.checkSelfUpdate() }) {
                                Text("Tentar novamente")
                            }
                        }
                        is SelfUpdateState.Available -> {
                            AvailableReleaseCard(
                                release = state.release,
                                download = selfUpdateDownload,
                                onDownload = { release ->
                                    val fileName = AppUpdatesViewModel.buildApkFileName(
                                        release.apkUrl!!, "HyperOS-Updater", release.version
                                    )
                                    viewModel.downloadManager.startDownload(
                                        release.apkUrl, fileName, "SELFUPDATE", "HyperOS Updater"
                                    )
                                },
                                onDismiss = { viewModel.downloadManager.dismissDownload("SELFUPDATE") }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Apps ocultos Section (collapsible + searchable)
            var hiddenExpanded by remember { mutableStateOf(false) }
            var hiddenQuery by remember { mutableStateOf("") }
            val hiddenLabels = remember(blacklisted) {
                blacklisted.associateWith { pkg ->
                    try {
                        val pm = context.packageManager
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Apps ocultos (${blacklisted.size})", style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { hiddenExpanded = !hiddenExpanded }) {
                            Icon(
                                if (hiddenExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (hiddenExpanded) "Recolher" else "Expandir"
                            )
                        }
                    }
                    if (hiddenExpanded) {
                        if (blacklisted.isEmpty()) {
                            Text("Nenhum app oculto",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = hiddenQuery,
                                onValueChange = { hiddenQuery = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Pesquisar...") },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val filtered = blacklisted.filter { pkg ->
                                hiddenQuery.isBlank() ||
                                    (hiddenLabels[pkg]?.contains(hiddenQuery, ignoreCase = true) == true) ||
                                    pkg.contains(hiddenQuery, ignoreCase = true)
                            }
                            if (filtered.isEmpty()) {
                                Text("Nenhum resultado",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            filtered.forEach { pkg ->
                                val appLabel = hiddenLabels[pkg]
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(appLabel ?: pkg, style = MaterialTheme.typography.bodyMedium)
                                        if (appLabel != null) {
                                            Text(pkg, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    IconButton(onClick = { viewModel.unhideApp(pkg) }) {
                                        Icon(Icons.Default.Visibility, contentDescription = "Mostrar app",
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Versões ignoradas Section (collapsible)
            var skippedExpanded by remember { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Versões ignoradas (${skipped.size})", style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { skippedExpanded = !skippedExpanded }) {
                            Icon(
                                if (skippedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (skippedExpanded) "Recolher" else "Expandir"
                            )
                        }
                    }
                    if (skippedExpanded) {
                        if (skipped.isEmpty()) {
                            Text("Nenhuma versão ignorada",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                        } else {
                            skipped.forEach { entry ->
                                val pkg = entry.substringBefore('|')
                                val version = entry.substringAfter('|', "")
                                val appLabel = remember(pkg) {
                                    try {
                                        val pm = context.packageManager
                                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(appLabel ?: pkg, style = MaterialTheme.typography.bodyMedium)
                                        Text("versão $version ignorada",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { viewModel.unskipVersion(pkg, version) }) {
                                        Icon(Icons.Default.Restore, contentDescription = "Parar de ignorar",
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Suporte Section: log sharing
            Text("Suporte", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Precisa reportar um problema? Envie os logs do app para o desenvolvedor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.shareLogs(context) },
                        enabled = !isGeneratingLogs
                    ) {
                        if (isGeneratingLogs) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Gerando logs...")
                        } else {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Compartilhar logs")
                        }
                    }
                    if (logShareError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            logShareError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // App Info
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("HyperOS Updater", style = MaterialTheme.typography.bodyMedium)
                    Text("Version 1.0 · build ${com.hyperos.updater.BuildConfig.BUILD_TIME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Checks OTA system updates and installed app updates from multiple sources.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AvailableReleaseCard(
    release: SelfUpdateRelease,
    download: com.hyperos.updater.domain.ActiveDownload?,
    onDownload: (SelfUpdateRelease) -> Unit,
    onDismiss: () -> Unit
) {
    var notesExpanded by remember { mutableStateOf(false) }

    Column {
        Text(
            "Nova versão disponível: v${release.version}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        if (release.publishedAt != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "Publicada em ${release.publishedAt.take(10)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Download progress or status (above release notes)
        if (download != null) {
            val dl = download
            when (dl.progress.status) {
                DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING -> {
                    val pct = if (dl.progress.totalBytes > 0)
                        (dl.progress.bytesDownloaded * 100 / dl.progress.totalBytes).toInt() else 0
                    LinearProgressIndicator(
                        progress = { if (dl.progress.totalBytes > 0) dl.progress.bytesDownloaded.toFloat() / dl.progress.totalBytes else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "$pct% · ${dl.progress.bytesDownloaded.toHumanReadableSize()} / ${dl.progress.totalBytes.toHumanReadableSize()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DownloadStatus.INSTALLING, DownloadStatus.AWAITING_INSTALL -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Instalando…", style = MaterialTheme.typography.labelSmall)
                }
                DownloadStatus.COMPLETED -> {
                    Text("Instalado ✓", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = onDismiss) {
                        Text("Ok")
                    }
                }
                DownloadStatus.ERROR -> {
                    Text(
                        "Erro: ${dl.progress.errorMessage ?: "falha desconhecida"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDownload(release) }) {
                            Text("Tentar novamente")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Fechar")
                        }
                    }
                }
                DownloadStatus.CANCELLED -> {
                    Text("Download cancelado", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(onClick = { onDownload(release) }) {
                        Text("Baixar novamente")
                    }
                }
            }
        }

        // Release notes (collapsible)
        if (!release.changelog.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { notesExpanded = !notesExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Notas da versão", style = MaterialTheme.typography.bodyMedium)
                Icon(
                    if (notesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (notesExpanded) "Recolher" else "Expandir"
                )
            }
            if (notesExpanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    release.changelog,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
