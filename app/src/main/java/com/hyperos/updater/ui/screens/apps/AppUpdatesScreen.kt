package com.hyperos.updater.ui.screens.apps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.ui.components.AppListItem
import com.hyperos.updater.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdatesScreen(
    navController: NavController,
    viewModel: AppUpdatesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkAllApps()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("App Updates") })
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            state.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.checkAllApps() }) {
                            Text("Retry")
                        }
                    }
                }
            }
            state.updates.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No apps found", style = MaterialTheme.typography.bodyLarge)
                }
            }
            else -> {
                val updatable = state.updates.count {
                    it.updateSource != UpdateSource.UNTRACKED &&
                            it.currentVersion != it.latestVersion
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("${state.updates.size} apps", style = MaterialTheme.typography.labelLarge)
                            if (updatable > 0) {
                                AssistChip(onClick = {}, label = { Text("$updatable updates") })
                            }
                        }
                    }
                    items(state.updates, key = { it.packageName + it.appType.name }) { update ->
                        AppListItem(
                            update = update,
                            isDownloading = state.downloading.containsKey(update.packageName),
                            onClick = { navController.navigate(Screen.AppDetail.createRoute(update.packageName)) },
                            onInstall = { viewModel.downloadAndInstall(update) }
                        )
                    }
                }
            }
        }
    }
}
