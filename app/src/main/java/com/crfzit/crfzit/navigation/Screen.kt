// app/src/main/java/com/crfzit/crfzit/navigation/Screen.kt
package com.crfzit.crfzit.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
// 【核心优化】导入我们自己的图标对象
import com.crfzit.crfzit.ui.icons.AppIcons

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", "主页", Icons.Default.Dashboard)
    // 【核心优化】使用 AppIcons.Tune
    data object Configuration : Screen("configuration", "配置", AppIcons.Tune) 
    data object Logs : Screen("logs", "日志", Icons.AutoMirrored.Filled.ListAlt)
    data object Settings : Screen("settings", "设置", Icons.Default.Settings)
    // 【核心优化】使用 AppIcons.Style
    data object ProfileManagement : Screen("profile_management", "情景模式", AppIcons.Style)
}