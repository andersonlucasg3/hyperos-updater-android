package com.hyperos.updater.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hyperos.updater.util.ShizukuHelper

@Composable
fun ShizukuStatusIcon(modifier: Modifier = Modifier) {
    val ready = ShizukuHelper.isReady()
    Icon(
        if (ready) Icons.Default.Security else Icons.Default.Warning,
        contentDescription = if (ready) "Shizuku ready" else "Shizuku not configured",
        tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = modifier
    )
}
