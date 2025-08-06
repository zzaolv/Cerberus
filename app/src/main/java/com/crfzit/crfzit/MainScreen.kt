// app/src/main/java/com/crfzit/crfzit/MainScreen.kt
package com.crfzit.crfzit

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crfzit.crfzit.navigation.Screen
import com.crfzit.crfzit.ui.configuration.ConfigurationScreen
import com.crfzit.crfzit.ui.configuration.ConfigurationViewModel
import com.crfzit.crfzit.ui.dashboard.DashboardScreen
import com.crfzit.crfzit.ui.dashboard.DashboardViewModel
import com.crfzit.crfzit.ui.logs.LogsScreen
import com.crfzit.crfzit.ui.settings.SettingsScreen
import com.crfzit.crfzit.ui.settings.SettingsViewModel
// [核心新增] 引入新页面的Composable
import com.crfzit.crfzit.ui.settings.more.MoreSettingsScreen
import com.crfzit.crfzit.ui.settings.more.MoreSettingsViewModel


@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val screens = listOf(
        Screen.Dashboard,
        Screen.Configuration,
        Screen.Logs,
        Screen.Settings
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                val dashboardViewModel: DashboardViewModel = viewModel()
                DashboardScreen(viewModel = dashboardViewModel)
            }
            composable(Screen.Configuration.route) {
                val configViewModel: ConfigurationViewModel = viewModel()
                ConfigurationScreen(navController = navController, viewModel = configViewModel)
            }
            composable(Screen.Logs.route) { LogsScreen() }
            composable(Screen.Settings.route) { 
                val settingsViewModel: SettingsViewModel = viewModel()
                SettingsScreen(viewModel = settingsViewModel)
            }
            // [核心新增] 添加新页面的导航 composable
            composable(Screen.MoreSettings.route) {
                val moreSettingsViewModel: MoreSettingsViewModel = viewModel()
                MoreSettingsScreen(
                    viewModel = moreSettingsViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}