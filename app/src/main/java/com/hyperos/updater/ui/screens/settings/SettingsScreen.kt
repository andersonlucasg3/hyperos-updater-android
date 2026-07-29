package com.hyperos.updater.ui.screens.settings

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val rootAvailable by viewModel.rootAvailable.collectAsState()
    val autoUpdateEnabled by viewModel.autoUpdateEnabled.collectAsState()

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
            Text("Root", style = MaterialTheme.typography.titleLarge)

            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = when (rootAvailable) {
                    true -> MaterialTheme.colorScheme.primaryContainer
                    false -> MaterialTheme.colorScheme.errorContainer
                    null -> MaterialTheme.colorScheme.surfaceVariant
                })
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (rootAvailable) {
                            true -> Icons.Default.CheckCircle
                            false -> Icons.Default.Warning
                            null -> Icons.Default.HourglassEmpty
                        }, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(when (rootAvailable) {
                            true -> "Root disponível"
                            false -> "Root não disponível"
                            null -> "Verificando..."
                        }, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        when (rootAvailable) {
                            true -> "O acesso root está funcionando. Instalações silenciosas via su estão disponíveis."
                            false -> "O app não detectou acesso root. Certifique-se de que o Magisk/KernelSU está instalado e conceda permissão quando solicitado."
                            null -> "A verificar o estado do root..."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.requestRootAccess() },
                            modifier = Modifier.weight(1f)
                        ) { Text("Solicitar acesso root") }
                        OutlinedButton(
                            onClick = { viewModel.refreshRootStatus() }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Verificar novamente")
                        }
                    }
                }
            }

            // Auto Update Section
            Text("App Updates", style = MaterialTheme.typography.titleLarge)
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
