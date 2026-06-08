package com.hyperos.updater.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hyperos.updater.domain.model.AppUpdate
import com.hyperos.updater.domain.model.UpdateSource

@Composable
fun AppListItem(
    update: AppUpdate,
    isDownloading: Boolean,
    onInstall: () -> Unit,
    onClick: () -> Unit,
    onOpenSource: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val hasUpdate = update.currentVersion != update.latestVersion

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(update.appName, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (hasUpdate) "${update.currentVersion} → ${update.latestVersion}"
                        else update.currentVersion,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SourceBadge(update.updateSource)
                        if (update.appType == com.hyperos.updater.domain.model.AppType.SYSTEM) {
                            SourceBadge(UpdateSource.TRACKER)
                        }
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                when {
                    isDownloading -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    else -> {
                        if (onOpenSource != null && update.downloadUrl != null) {
                            IconButton(onClick = onOpenSource) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = "Open source", modifier = Modifier.size(20.dp))
                            }
                        }
                        if (hasUpdate) {
                            IconButton(onClick = onInstall) {
                                Icon(Icons.Default.Download, contentDescription = "Install")
                            }
                        }
                    }
                }
            }

            // Expandable detail section
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    DetailRow("Package", update.packageName)
                    DetailRow("Source", update.updateSource.name)
                    DetailRow("Current", update.currentVersion)
                    if (hasUpdate) {
                        DetailRow("Latest", update.latestVersion)
                    }

                    if (!hasUpdate && update.updateSource != UpdateSource.UNTRACKED) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Up to date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text("$label: ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SourceBadge(source: UpdateSource) {
    val (text, color) = when (source) {
        UpdateSource.APKMIRROR -> "APKMirror" to MaterialTheme.colorScheme.primary
        UpdateSource.APKPURE -> "APKPure" to MaterialTheme.colorScheme.primary
        UpdateSource.APKCOMBO -> "APKCombo" to MaterialTheme.colorScheme.tertiary
        UpdateSource.FDROID -> "F-Droid" to MaterialTheme.colorScheme.secondary
        UpdateSource.TRACKER -> "System" to MaterialTheme.colorScheme.secondary
        UpdateSource.UNTRACKED -> "not tracked" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
