package com.hyperos.updater.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hyperos.updater.util.toHumanReadableSize

data class DownloadProgress(
    val fileName: String = "",
    val progress: Int = 0,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val speedBytesPerSec: Long = 0,
    val status: DownloadStatus = DownloadStatus.PREPARING
)

enum class DownloadStatus {
    PREPARING, DOWNLOADING, COMPLETED, INSTALLING, ERROR, CANCELLED
}

fun DownloadStatus.isOngoing() = this == DownloadStatus.PREPARING || this == DownloadStatus.DOWNLOADING || this == DownloadStatus.INSTALLING
fun DownloadStatus.isTerminal() = this == DownloadStatus.COMPLETED || this == DownloadStatus.ERROR || this == DownloadStatus.CANCELLED

@Composable
fun DownloadProgressSheet(
    progress: DownloadProgress?,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    if (progress == null) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status icon
            Icon(
                when (progress.status) {
                    DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING, DownloadStatus.INSTALLING -> Icons.Default.Download
                    DownloadStatus.COMPLETED -> Icons.Default.CheckCircle
                    DownloadStatus.ERROR -> Icons.Default.Error
                    DownloadStatus.CANCELLED -> Icons.Default.Cancel
                },
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = when (progress.status) {
                    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                    DownloadStatus.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Status text
            Text(
                when (progress.status) {
                    DownloadStatus.PREPARING -> "Preparing download..."
                    DownloadStatus.DOWNLOADING -> "Downloading ${progress.fileName}"
                    DownloadStatus.INSTALLING -> "Installing..."
                    DownloadStatus.COMPLETED -> "Download complete"
                    DownloadStatus.ERROR -> "Download failed"
                    DownloadStatus.CANCELLED -> "Download cancelled"
                },
                style = MaterialTheme.typography.titleSmall
            )

            // Progress bar
            if (progress.status == DownloadStatus.DOWNLOADING || progress.status == DownloadStatus.PREPARING) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { if (progress.totalBytes > 0) progress.bytesDownloaded.toFloat() / progress.totalBytes else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${progress.bytesDownloaded.toHumanReadableSize()} / ${progress.totalBytes.toHumanReadableSize()}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        "${progress.speedBytesPerSec.toHumanReadableSize()}/s",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Actions
            if (progress.status == DownloadStatus.DOWNLOADING || progress.status == DownloadStatus.PREPARING) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cancel")
                }
            }

            if (progress.status == DownloadStatus.COMPLETED || progress.status == DownloadStatus.ERROR ||
                progress.status == DownloadStatus.CANCELLED) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}
