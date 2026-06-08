package com.hyperos.updater.ui.screens.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hyperos.updater.util.ShizukuHelper
import com.hyperos.updater.util.ShizukuState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var shizukuState by remember { mutableStateOf(ShizukuHelper.checkState()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            // Shizuku Section
            Text("Shizuku", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (shizukuState) {
                                ShizukuState.READY -> Icons.Default.Security
                                ShizukuState.NOT_INSTALLED, ShizukuState.INSTALLED_NOT_RUNNING, ShizukuState.RUNNING_NO_PERMISSION, ShizukuState.CHECKING -> Icons.Default.Warning
                            },
                            contentDescription = null,
                            tint = if (shizukuState == ShizukuState.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when (shizukuState) {
                                ShizukuState.NOT_INSTALLED -> "Shizuku not installed"
                                ShizukuState.INSTALLED_NOT_RUNNING -> "Shizuku not running"
                                ShizukuState.RUNNING_NO_PERMISSION -> "Shizuku permission not granted"
                                ShizukuState.READY -> "Shizuku ready"
                                ShizukuState.CHECKING -> "Checking..."
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    when (shizukuState) {
                        ShizukuState.NOT_INSTALLED -> Button(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                            context.startActivity(intent)
                        }) { Text("Download Shizuku") }
                        ShizukuState.INSTALLED_NOT_RUNNING -> Button(onClick = {
                            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            if (intent != null) context.startActivity(intent)
                        }) { Text("Open Shizuku") }
                        ShizukuState.RUNNING_NO_PERMISSION -> Button(onClick = {
                            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            if (intent != null) context.startActivity(intent)
                        }) { Text("Grant Permission") }
                        ShizukuState.READY -> Text("Shizuku is ready for silent installs", color = MaterialTheme.colorScheme.primary)
                        ShizukuState.CHECKING -> Text("Checking Shizuku status...")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { shizukuState = ShizukuHelper.checkState() }) {
                        Text("Refresh Status")
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
                    Text("Version 1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Checks OTA system updates and installed app updates from multiple sources.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
