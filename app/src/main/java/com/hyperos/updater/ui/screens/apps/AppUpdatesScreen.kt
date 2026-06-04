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
import com.hyperos.updater.domain.model.AppUpdate
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.ui.components.AppListItem
import com.hyperos.updater.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdatesScreen(
    navController: NavController,
    viewModel: AppUpdatesViewModel = hiltViewModel()
) {
    val appList = viewModel.appList // SnapshotStateList — element-level tracking
    val isScanning by viewModel.isScanning.collectAsState()
    val error by viewModel.error.collectAsState()

    val sortedUpdates by remember {
        derivedStateOf {
            appList.sortedWith(
                compareByDescending<AppUpdate> { it.updateSource != UpdateSource.UNTRACKED && it.currentVersion != it.latestVersion }
                    .thenBy { it.appName.lowercase() }
            )
        }
    }

    LaunchedEffect(Unit) { viewModel.checkAllApps() }

    Scaffold(topBar = { TopAppBar(title = { Text("App Updates") }) }) { padding ->
        when {
            isScanning && sortedUpdates.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { viewModel.checkAllApps() }) { Text("Retry") }
                } }
            sortedUpdates.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No apps found") }
            else -> {
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        val updateCount = sortedUpdates.count { it.updateSource != UpdateSource.UNTRACKED && it.currentVersion != it.latestVersion }
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${sortedUpdates.size} apps", style = MaterialTheme.typography.labelLarge)
                            if (isScanning) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            if (updateCount > 0) AssistChip(onClick = {}, label = { Text("$updateCount updates") })
                        }
                    }
                    items(sortedUpdates, key = { it.packageName + it.appType.name }) { update ->
                        AppListItem(update = update, isDownloading = false, onClick = {
                            navController.navigate(Screen.AppDetail.createRoute(update.packageName))
                        }, onInstall = { })
                    }
                }
            }
        }
    }
}
