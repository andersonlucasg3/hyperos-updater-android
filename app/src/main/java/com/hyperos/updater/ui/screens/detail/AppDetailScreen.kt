package com.hyperos.updater.ui.screens.detail

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.ui.components.DownloadStatus
import com.hyperos.updater.ui.components.PackageAppIcon
import com.hyperos.updater.ui.components.SourceBadge
import com.hyperos.updater.ui.components.UrlAppIcon
import com.hyperos.updater.ui.components.isOngoing
import com.hyperos.updater.util.toHumanReadableSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    viewModel: AppDetailViewModel,
    onBack: () -> Unit,
    onDownloadViaWebView: (key: String, appName: String, version: String, url: String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val skipDone by viewModel.skipDone.collectAsState()
    val hideDone by viewModel.hideDone.collectAsState()
    val downloads by viewModel.downloadManager.downloads.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(skipDone) { if (skipDone) onBack() }
    LaunchedEffect(hideDone) { if (hideDone) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.appName.ifBlank { "Detalhes do App" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        }
    ) { padding ->
        if (state.isChecking && state.appName.isBlank()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        state.error?.let { error ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Header ─────────────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (state.isInstalled && !state.isSearchOrigin) {
                            PackageAppIcon(state.packageName)
                        } else {
                            UrlAppIcon(state.iconUrl)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(state.appName, style = MaterialTheme.typography.headlineMedium)
                        if (state.packageName.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                state.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SourceBadge(state.primarySource)
                            if (state.isSystemApp) SourceBadge(UpdateSource.TRACKER)
                        }
                        if (state.isInstalled) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Instalada: ${state.installedVersion ?: state.currentVersion}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (state.installedVersionCode > 0) {
                                Text(
                                    "Código: ${state.installedVersionCode}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!state.installerPackage.isNullOrBlank()) {
                                Text(
                                    "Instalador: ${state.installerPackage}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ── Update status ──────────────────────────────────────────────
            item {
                val hasUpdate = state.currentVersion != state.latestVersion && state.latestVersion.isNotBlank()
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Status da Versão", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (state.isChecking) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Verificando...", style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (hasUpdate) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Atual", style = MaterialTheme.typography.labelSmall)
                                    Text(state.currentVersion, style = MaterialTheme.typography.bodyLarge)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Disponível", style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        state.latestVersion,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else if (state.isInstalled) {
                            Text("Atualizado", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                        } else if (state.isSearchOrigin && state.latestVersion.isNotBlank()) {
                            // Search origin, app not installed: show what the source offers
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("Disponível", style = MaterialTheme.typography.labelSmall)
                                    Text(state.latestVersion, style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                                state.searchSource?.let {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    SourceBadge(it)
                                }
                            }
                        }
                    }
                }
            }

            // ── Actions ────────────────────────────────────────────────────
            if (state.isInstalled && !state.isSearchOrigin) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Ações", style = MaterialTheme.typography.titleMedium)
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.latestVersion != state.currentVersion && state.latestVersion.isNotBlank()) {
                            OutlinedButton(onClick = { viewModel.skipVersion() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.outline
                                )) {
                                Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pular ${state.latestVersion}")
                            }
                        }
                        OutlinedButton(onClick = { viewModel.hideApp() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )) {
                            Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Ocultar app")
                        }
                        OutlinedButton(onClick = { viewModel.recheck() },
                            modifier = Modifier.fillMaxWidth()) {
                            if (state.isChecking) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Verificar novamente")
                        }
                    }
                }
            }

            // ── Versões por fonte (search-origin: all hits) ──────────────────
            if (state.isSearchOrigin && state.searchHits.isNotEmpty()) {
                item {
                    Text("Versões por Fonte", style = MaterialTheme.typography.titleMedium)
                }
                items(state.searchHits) { hit ->
                    val hitKey = hit.source.name + state.appName
                    val hitDl = downloads[hitKey]
                    val hitVersion = hit.versionName ?: state.latestVersion
                    val skipKey = state.packageName + "|" + (hit.versionName ?: "")
                    val isSkipped = hit.versionName != null && skipKey in state.skippedVersions
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SourceBadge(hit.source)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    if (hit.versionName != null) {
                                        Text(
                                            hit.versionName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            textDecoration = if (isSkipped) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                                            color = if (isSkipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isSkipped) {
                                            Text("ignorada", style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline)
                                        }
                                    }
                                    Text(hit.downloadPageUrl, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1)
                                }
                                if (hit.versionName != null && !isSkipped) {
                                    IconButton(onClick = { viewModel.skipSpecificVersion(hit.versionName) }) {
                                        Icon(Icons.Default.Block, contentDescription = "Ignorar versão",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.outline)
                                    }
                                }
                                if (hitDl != null && hitDl.progress.status.isOngoing()) {
                                    Text("${hitDl.progress.progress}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                } else if (hitDl?.progress?.status == DownloadStatus.COMPLETED) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                } else if (hitDl?.progress?.status == DownloadStatus.ERROR) {
                                    IconButton(onClick = { viewModel.downloadManager.dismissDownload(hitKey) }) {
                                        Icon(Icons.Default.Error, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    }
                                } else if (hitDl?.progress?.status == DownloadStatus.AWAITING_INSTALL) {
                                    IconButton(onClick = { viewModel.downloadManager.retryInstall(hitKey) }) {
                                        Icon(Icons.Default.InstallMobile, contentDescription = "Instalar",
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                } else {
                                    IconButton(onClick = {
                                        viewModel.downloadFromSource(
                                            hit.source, hit.downloadPageUrl, state.appName,
                                            hitVersion, hitKey
                                        ) { key, appName, version, url ->
                                            onDownloadViaWebView(key, appName, version, url)
                                        }
                                    }) {
                                        Icon(Icons.Default.Download, contentDescription = "Baixar",
                                            modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                            // Inline download progress
                            if (hitDl != null && hitDl.progress.status.isOngoing()) {
                                if (hitDl.progress.status == DownloadStatus.INSTALLING) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Instalando...", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 12.dp))
                                } else {
                                    LinearProgressIndicator(
                                        progress = { if (hitDl.progress.totalBytes > 0) hitDl.progress.bytesDownloaded.toFloat() / hitDl.progress.totalBytes else 0f },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${hitDl.progress.progress}%", style = MaterialTheme.typography.labelSmall)
                                        Text(hitDl.progress.bytesDownloaded.toHumanReadableSize(), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            if (hitDl?.progress?.status == DownloadStatus.ERROR && hitDl.progress.errorMessage != null) {
                                Text("Erro: ${hitDl.progress.errorMessage}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 12.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }

            // ── Versões por fonte (installed app) ─────────────────────────────
            if (state.sourceVersions.isNotEmpty()) {
                item {
                    Text("Versões por Fonte", style = MaterialTheme.typography.titleMedium)
                }
                items(state.sourceVersions) { sv ->
                    val svKey = sv.source.name + state.appName
                    val svDl = downloads[svKey]
                    val svSkipKey = state.packageName + "|" + sv.version
                    val svIsSkipped = svSkipKey in state.skippedVersions
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SourceBadge(sv.source)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                sv.version,
                                style = MaterialTheme.typography.bodyMedium,
                                textDecoration = if (svIsSkipped) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                                color = if (sv.version != state.currentVersion && state.isInstalled)
                                    MaterialTheme.colorScheme.primary
                                else if (svIsSkipped) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (svIsSkipped) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("ignorada", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                            if (!svIsSkipped) {
                                IconButton(onClick = { viewModel.skipSpecificVersion(sv.version) }) {
                                    Icon(Icons.Default.Block, contentDescription = "Ignorar versão",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.outline)
                                }
                            }
                            if (sv.downloadUrl != null && (!state.isInstalled || sv.version != state.currentVersion)) {
                                if (svDl != null && svDl.progress.status.isOngoing()) {
                                    Text("${svDl.progress.progress}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                } else if (svDl?.progress?.status == DownloadStatus.COMPLETED) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                } else if (svDl?.progress?.status == DownloadStatus.ERROR) {
                                    IconButton(onClick = { viewModel.downloadManager.dismissDownload(svKey) }) {
                                        Icon(Icons.Default.Error, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    }
                                } else if (svDl?.progress?.status == DownloadStatus.AWAITING_INSTALL) {
                                    IconButton(onClick = { viewModel.downloadManager.retryInstall(svKey) }) {
                                        Icon(Icons.Default.InstallMobile, contentDescription = "Instalar",
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                } else {
                                    IconButton(onClick = {
                                        val pageUrl = viewModel.getSourcePageUrl(
                                            state.packageName, sv.source, sv.downloadUrl
                                        )
                                        viewModel.downloadFromSource(
                                            sv.source, pageUrl, state.appName, sv.version, svKey
                                        ) { key, appName, version, url ->
                                            onDownloadViaWebView(key, appName, version, url)
                                        }
                                    }) {
                                        Icon(Icons.Default.Download, contentDescription = "Baixar",
                                            modifier = Modifier.size(20.dp))
                                    }
                                }
                            } else if (sv.version == state.currentVersion && state.isInstalled) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "Instalada",
                                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                        // Inline download progress
                        if (svDl != null && svDl.progress.status.isOngoing()) {
                            if (svDl.progress.status == DownloadStatus.INSTALLING) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Instalando...", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 12.dp))
                            } else {
                                LinearProgressIndicator(
                                    progress = { if (svDl.progress.totalBytes > 0) svDl.progress.bytesDownloaded.toFloat() / svDl.progress.totalBytes else 0f },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${svDl.progress.progress}%", style = MaterialTheme.typography.labelSmall)
                                    Text(svDl.progress.bytesDownloaded.toHumanReadableSize(), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        if (svDl?.progress?.status == DownloadStatus.ERROR && svDl.progress.errorMessage != null) {
                            Text("Erro: ${svDl.progress.errorMessage}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 12.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }

            // ── Histórico de versões ───────────────────────────────────────
            // MemeOS
            if (state.memeosHistory.isNotEmpty()) {
                item { HistoryGroupHeader("MemeOS", state.isLoadingMemeosHistory) }
                items(state.memeosHistory) { entry ->
                    val dlKey = UpdateSource.MEMEOS.name + state.appName + entry.version
                    val dl = downloads[dlKey]
                    val skipKey = state.packageName + "|" + entry.version
                    val isSkipped = skipKey in state.skippedVersions
                    val isInstalledVer = state.isInstalled &&
                        (entry.version == state.installedVersion || entry.version == state.currentVersion)
                    VersionHistoryCard(
                        version = entry.version,
                        subtitle = "${entry.region} · ${entry.date}" +
                            (entry.sizeBytes?.let { " · ${it.toHumanReadableSize()}" } ?: ""),
                        isInstalled = isInstalledVer,
                        isSkipped = isSkipped,
                        onSkipVersion = { viewModel.skipSpecificVersion(entry.version) },
                        downloadKey = dlKey,
                        download = dl,
                        onDownload = { viewModel.downloadFromSource(
                            UpdateSource.MEMEOS, entry.pageUrl, state.appName, entry.version, dlKey
                        ) { key, appName, version, url ->
                            onDownloadViaWebView(key, appName, version, url)
                        }},
                        onCancel = { viewModel.downloadManager.cancelDownload(dlKey) },
                        onRetry = { viewModel.downloadManager.retryInstall(dlKey) },
                        onDismiss = { viewModel.downloadManager.dismissDownload(dlKey) }
                    )
                }
            }

            // F-Droid
            if (state.fdroidHistory.isNotEmpty()) {
                item { HistoryGroupHeader("F-Droid", state.isLoadingFdroidHistory) }
                items(state.fdroidHistory) { entry ->
                    val dlKey = UpdateSource.FDROID.name + state.appName + entry.versionName
                    val dl = downloads[dlKey]
                    val skipKey = state.packageName + "|" + entry.versionName
                    val isSkipped = skipKey in state.skippedVersions
                    val isInstalledVer = state.isInstalled &&
                        (entry.versionName == state.installedVersion || entry.versionName == state.currentVersion)
                    VersionHistoryCard(
                        version = entry.versionName,
                        subtitle = "Código: ${entry.versionCode}",
                        isInstalled = isInstalledVer,
                        isSkipped = isSkipped,
                        onSkipVersion = { viewModel.skipSpecificVersion(entry.versionName) },
                        downloadKey = dlKey,
                        download = dl,
                        onDownload = {
                            if (entry.apkUrl != null) {
                                val filename = AppDetailViewModel.buildApkFileName(entry.apkUrl, state.appName, entry.versionName)
                                viewModel.downloadManager.startDownload(entry.apkUrl, filename, dlKey, state.appName)
                            }
                        },
                        onCancel = { viewModel.downloadManager.cancelDownload(dlKey) },
                        onRetry = { viewModel.downloadManager.retryInstall(dlKey) },
                        onDismiss = { viewModel.downloadManager.dismissDownload(dlKey) }
                    )
                }
            }

            // GitHub
            if (state.githubHistory.isNotEmpty()) {
                item { HistoryGroupHeader("GitHub", state.isLoadingGithubHistory) }
                items(state.githubHistory) { entry ->
                    val dlKey = UpdateSource.GITHUB.name + state.appName + entry.tag
                    val dl = downloads[dlKey]
                    val versionDisplay = entry.tag.removePrefix("v").removePrefix("V")
                    val skipKey = state.packageName + "|" + versionDisplay
                    val isSkipped = skipKey in state.skippedVersions
                    val isInstalledVer = state.isInstalled &&
                        (versionDisplay == state.installedVersion || versionDisplay == state.currentVersion)
                    VersionHistoryCard(
                        version = versionDisplay,
                        subtitle = entry.name + (entry.publishedAt?.let { " · $it" } ?: ""),
                        isInstalled = isInstalledVer,
                        isSkipped = isSkipped,
                        onSkipVersion = { viewModel.skipSpecificVersion(versionDisplay) },
                        downloadKey = dlKey,
                        download = dl,
                        onDownload = {
                            if (entry.apkUrl != null) {
                                val filename = AppDetailViewModel.buildApkFileName(entry.apkUrl, state.appName, versionDisplay)
                                viewModel.downloadManager.startDownload(entry.apkUrl, filename, dlKey, state.appName)
                            }
                        },
                        onCancel = { viewModel.downloadManager.cancelDownload(dlKey) },
                        onRetry = { viewModel.downloadManager.retryInstall(dlKey) },
                        onDismiss = { viewModel.downloadManager.dismissDownload(dlKey) }
                    )
                }
            }

            // APKMirror
            if (state.apkmirrorHistory.isNotEmpty()) {
                item { HistoryGroupHeader("APKMirror", state.isLoadingApkmirrorHistory) }
                items(state.apkmirrorHistory) { entry ->
                    val dlKey = UpdateSource.APKMIRROR.name + state.appName + entry.version
                    val dl = downloads[dlKey]
                    val skipKey = state.packageName + "|" + entry.version
                    val isSkipped = skipKey in state.skippedVersions
                    val isInstalledVer = state.isInstalled &&
                        (entry.version == state.installedVersion || entry.version == state.currentVersion)
                    VersionHistoryCard(
                        version = entry.version,
                        subtitle = "APKMirror",
                        isInstalled = isInstalledVer,
                        isSkipped = isSkipped,
                        onSkipVersion = { viewModel.skipSpecificVersion(entry.version) },
                        downloadKey = dlKey,
                        download = dl,
                        onDownload = {
                            // APKMirror always needs WebView download
                            onDownloadViaWebView(dlKey, state.appName, entry.version, entry.pageUrl)
                        },
                        onCancel = { viewModel.downloadManager.cancelDownload(dlKey) },
                        onRetry = { viewModel.downloadManager.retryInstall(dlKey) },
                        onDismiss = { viewModel.downloadManager.dismissDownload(dlKey) }
                    )
                }
            }

            // Other sources: single "latest" row + "abrir página de versões"
            val otherSources = state.sourceVersions
                .filter { it.source !in setOf(UpdateSource.MEMEOS, UpdateSource.FDROID, UpdateSource.GITHUB, UpdateSource.APKMIRROR) }
            if (otherSources.isNotEmpty()) {
                item { Text("Outras Fontes", style = MaterialTheme.typography.titleMedium) }
                items(otherSources) { sv ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SourceBadge(sv.source)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(sv.version, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                val url = sv.downloadUrl ?: "https://apkpure.com/apk/${state.packageName}"
                                viewModel.openSourcePage(url)
                            }) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null,
                                    modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Abrir página", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Loading indicators for empty histories
            if (state.isLoadingMemeosHistory && state.memeosHistory.isEmpty()) {
                item { LoadingHistoryItem("MemeOS") }
            }
            if (state.isLoadingFdroidHistory && state.fdroidHistory.isEmpty()) {
                item { LoadingHistoryItem("F-Droid") }
            }
            if (state.isLoadingGithubHistory && state.githubHistory.isEmpty()) {
                item { LoadingHistoryItem("GitHub") }
            }
            if (state.isLoadingApkmirrorHistory && state.apkmirrorHistory.isEmpty()) {
                item { LoadingHistoryItem("APKMirror") }
            }



            // Search-origin download button (only when no hits array — legacy single-source)
            if (state.isSearchOrigin && state.searchSource != null && state.searchPageUrl != null && state.searchHits.isEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    val dlKey = state.searchSource!!.name + state.appName
                    val dl = downloads[dlKey]
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Baixar", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            if (dl != null && dl.progress.status.isOngoing()) {
                                if (dl.progress.status == DownloadStatus.INSTALLING) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Instalando...", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                } else {
                                    LinearProgressIndicator(
                                        progress = { if (dl.progress.totalBytes > 0) dl.progress.bytesDownloaded.toFloat() / dl.progress.totalBytes else 0f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${dl.progress.progress}%", style = MaterialTheme.typography.labelSmall)
                                        Text(dl.progress.bytesDownloaded.toHumanReadableSize(), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            } else if (dl?.progress?.status == DownloadStatus.COMPLETED) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Download concluído", style = MaterialTheme.typography.bodySmall)
                            } else if (dl?.progress?.status == DownloadStatus.ERROR) {
                                Text("Erro: ${dl.progress.errorMessage ?: "Falha no download"}",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            } else {
                                Button(onClick = {
                                    val version = state.latestVersion.ifBlank { state.currentVersion }
                                    viewModel.downloadFromSource(
                                        state.searchSource!!, state.searchPageUrl!!, state.appName, version, dlKey
                                    ) { key, appName, ver, url ->
                                        onDownloadViaWebView(key, appName, ver, url)
                                    }
                                }) {
                                    Icon(Icons.Default.Download, contentDescription = null,
                                        modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Baixar última versão")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Reusable components ───────────────────────────────────────────────────────

@Composable
private fun HistoryGroupHeader(sourceName: String, isLoading: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(sourceName, style = MaterialTheme.typography.titleMedium)
        if (isLoading) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun VersionHistoryCard(
    version: String,
    subtitle: String,
    isInstalled: Boolean,
    isSkipped: Boolean = false,
    onSkipVersion: (() -> Unit)? = null,
    downloadKey: String,
    download: com.hyperos.updater.domain.ActiveDownload?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            version,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (isSkipped) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                            color = if (isSkipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        if (isInstalled) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text("instalada",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (isSkipped) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text("ignorada",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    Text(subtitle, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onSkipVersion != null && !isInstalled && !isSkipped) {
                    IconButton(onClick = onSkipVersion) {
                        Icon(Icons.Default.Block, contentDescription = "Ignorar versão",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.outline)
                    }
                }
                if (!isInstalled) {
                    when {
                        download?.progress?.status == DownloadStatus.INSTALLING -> {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        }
                        download?.progress?.status?.isOngoing() == true -> {
                            IconButton(onClick = onCancel) {
                                Icon(Icons.Default.Cancel, contentDescription = "Cancelar",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        download?.progress?.status == DownloadStatus.COMPLETED -> {
                            Icon(Icons.Default.CheckCircle, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        download?.progress?.status == DownloadStatus.AWAITING_INSTALL -> {
                            IconButton(onClick = onRetry) {
                                Icon(Icons.Default.InstallMobile, contentDescription = "Instalar",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        download?.progress?.status == DownloadStatus.ERROR -> {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Error, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        else -> {
                            IconButton(onClick = onDownload) {
                                Icon(Icons.Default.Download, contentDescription = "Baixar",
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            // Progress bar
            if (download != null && download.progress.status.isOngoing()) {
                Spacer(modifier = Modifier.height(4.dp))
                if (download.progress.status == DownloadStatus.INSTALLING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Instalando...", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                } else {
                    LinearProgressIndicator(
                        progress = { if (download.progress.totalBytes > 0) download.progress.bytesDownloaded.toFloat() / download.progress.totalBytes else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${download.progress.progress}%", style = MaterialTheme.typography.labelSmall)
                        Text("${download.progress.bytesDownloaded.toHumanReadableSize()}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (download?.progress?.status == DownloadStatus.ERROR && download.progress.errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Erro: ${download.progress.errorMessage}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LoadingHistoryItem(sourceName: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Carregando histórico do $sourceName...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
