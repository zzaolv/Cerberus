package com.crfzit.crfzit.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style // <-- 关键修正：添加 import
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", "主页", Icons.Default.Dashboard)
    data object Configuration : Screen("configuration", "配置", Icons.Default.Tune)
    data object Logs : Screen("logs", "日志", Icons.AutoMirrored.Filled.ListAlt)
    data object Settings : Screen("settings", "设置", Icons.Default.Settings)
    data object ProfileManagement : Screen("profile_management", "情景模式", Icons.Default.Style)
}