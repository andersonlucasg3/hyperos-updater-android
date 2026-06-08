package com.hyperos.updater.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.hyperos.updater.domain.DownloadManager

@Composable
fun DownloadsBadge(
    downloadManager: DownloadManager,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val downloads by downloadManager.downloads.collectAsState()
    val count = downloads.count { it.value.progress.status.isOngoing() }

    IconButton(onClick = onClick, modifier = modifier) {
        BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) {
            Icon(Icons.Default.Download, contentDescription = "Downloads")
        }
    }
}
