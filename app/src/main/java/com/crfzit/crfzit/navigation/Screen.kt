// app/src/main/java/com/crfzit/crfzit/navigation/Screen.kt
package com.crfzit.crfzit.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
// 【核心修复】导入我们自己的图标对象
import com.crfzit.crfzit.ui.icons.AppIcons

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    // 【核心修复】使用我们自己的 AppIcons 对象
    data object Dashboard : Screen("dashboard", "主页", AppIcons.Dashboard)
    data object Configuration : Screen("configuration", "配置", AppIcons.Tune)
    data object Logs : Screen("logs", "日志", AppIcons.ListAlt)
    // Settings 图标在 core 库中存在，可以直接用
    data object Settings : Screen("settings", "设置", Icons.Default.Settings)
    data object ProfileManagement : Screen("profile_management", "情景模式", AppIcons.Style)
}