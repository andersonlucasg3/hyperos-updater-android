package com.hyperos.updater.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.hyperos.updater.ui.screens.detail.AppDetailScreen
import com.hyperos.updater.ui.screens.downloads.DownloadsScreen
import com.hyperos.updater.ui.screens.home.HomeScreen
import com.hyperos.updater.ui.screens.search.AppSearchScreen
import com.hyperos.updater.ui.screens.settings.SettingsScreen

@Composable
fun HyperOsNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(navController)
        }
        composable(Screen.Search.route) {
            AppSearchScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Downloads.route) {
            DownloadsScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Screen.AppDetail.ROUTE,
            arguments = listOf(navArgument("packageName") { type = NavType.StringType })
        ) { backStackEntry ->
            val pkg = backStackEntry.arguments?.getString("packageName") ?: return@composable
            AppDetailScreen(packageName = pkg, onBack = { navController.popBackStack() })
        }
    }
}
