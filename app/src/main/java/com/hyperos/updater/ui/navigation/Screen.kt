package com.hyperos.updater.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object Downloads : Screen("downloads")
    data object Settings : Screen("settings")

    data class AppDetail(val packageName: String = "{packageName}") : Screen("app_detail/{packageName}") {
        companion object {
            const val ROUTE = "app_detail/{packageName}"
            fun createRoute(pkg: String) = "app_detail/$pkg"
        }
    }
}
