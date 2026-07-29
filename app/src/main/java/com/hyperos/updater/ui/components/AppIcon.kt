package com.hyperos.updater.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage

/** Icon of an installed app, resolved from PackageManager (cached per package). */
@Composable
fun PackageAppIcon(packageName: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        try {
            context.packageManager.getApplicationIcon(packageName).toBitmap().asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp))
        )
    } else {
        Icon(
            Icons.Default.Android,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size)
        )
    }
}

/** Icon from a remote URL (search results), with a generic fallback. */
@Composable
fun UrlAppIcon(iconUrl: String?, size: Dp = 40.dp) {
    if (iconUrl.isNullOrBlank()) {
        Icon(
            Icons.Default.Android,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size)
        )
    } else {
        AsyncImage(
            model = iconUrl,
            contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp))
        )
    }
}
