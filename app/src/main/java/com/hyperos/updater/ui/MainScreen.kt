package com.hyperos.updater.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.hyperos.updater.ui.screens.apps.UpdatesTab
import com.hyperos.updater.ui.screens.ota.OtaTab
import com.hyperos.updater.ui.screens.search.SearchTab
import com.hyperos.updater.ui.screens.settings.SettingsTab

@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                    label = { Text("OS Updates") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Find & Install") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                    label = { Text("Updates") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> OtaTab()
                1 -> SearchTab()
                2 -> UpdatesTab()
                3 -> SettingsTab()
            }
        }
    }
}
