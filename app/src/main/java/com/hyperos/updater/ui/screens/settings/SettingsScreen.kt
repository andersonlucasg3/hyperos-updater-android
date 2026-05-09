package com.hyperos.updater.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var shizukuState by remember { mutableStateOf(ShizukuHelper.checkState()) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1500)
        shizukuState = ShizukuHelper.checkState()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Shizuku Setup", style = MaterialTheme.typography.titleLarge)

            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = when (shizukuState) {
                    ShizukuState.READY -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                })
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (shizukuState) {
                            ShizukuState.READY -> Icons.Default.CheckCircle
                            else -> Icons.Default.Info
                        }, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(when (shizukuState) {
                            ShizukuState.NOT_INSTALLED -> "Not installed"
                            ShizukuState.INSTALLED_NOT_RUNNING -> "Not running"
                            ShizukuState.RUNNING_NO_PERMISSION -> "Permission needed"
                            ShizukuState.READY -> "Connected"
                            ShizukuState.CHECKING -> "Checking..."
                        }, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(ShizukuHelper.getSetupInstructions(shizukuState), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))

                    when (shizukuState) {
                        ShizukuState.NOT_INSTALLED ->
                            Button(onClick = { ShizukuHelper.openShizukuPlayStore(context) },
                                modifier = Modifier.fillMaxWidth()) { Text("Download Shizuku") }

                        ShizukuState.INSTALLED_NOT_RUNNING, ShizukuState.RUNNING_NO_PERMISSION -> {
                            Button(onClick = {
                                val i = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                if (i != null) context.startActivity(i)
                            }, modifier = Modifier.fillMaxWidth()) { Text("Open Shizuku") }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("In Shizuku: Authorized apps → enable HyperOS Updater → Refresh below",
                                style = MaterialTheme.typography.labelSmall)
                        }

                        ShizukuState.READY -> Text("Split APK install will work.", style = MaterialTheme.typography.bodySmall)
                        else -> {}
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(onClick = {
                        shizukuState = ShizukuState.CHECKING
                        shizukuState = ShizukuHelper.checkState()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Refresh Status")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Why Shizuku?", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Enables split APK install (APKM/XAPK/APKS). Without it, only single APKs work.", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Text("HyperOS Updater v1.0", style = MaterialTheme.typography.bodySmall)
                    Text("Xiaomi 17 Pro Max • popsicle", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
