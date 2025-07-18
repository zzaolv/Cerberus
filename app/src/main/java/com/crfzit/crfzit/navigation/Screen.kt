// app/src/main/java/com/crfzit/crfzit/navigation/Screen.kt
package com.crfzit.crfzit.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.crfzit.crfzit.ui.icons.AppIcons

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", "主页", AppIcons.Dashboard)
    data object Configuration : Screen("configuration", "配置", AppIcons.Tune)
    data object Logs : Screen("logs", "日志", AppIcons.ListAlt)
    data object Settings : Screen("settings", "设置", Icons.Default.Settings)
    // [FIX] Correctly refer to the icon defined in AppIcons
    data object ProfileManagement : Screen("profile_management", "情景模式", AppIcons.Style)
}