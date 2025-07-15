package com.crfzit.crfzit.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crfzit.crfzit.data.model.AppRuntimeState
import com.crfzit.crfzit.data.model.DisplayStatus
import com.crfzit.crfzit.data.model.GlobalStats
import com.crfzit.crfzit.ui.theme.CRFzitTheme
import java.util.Locale

// DashboardScreen 和 DashboardContent 的整体结构保持不变

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cerberus Dashboard") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            when {
                uiState.isLoading -> {
                    StatusIndicator("等待连接守护进程...", showProgress = true)
                }
                !uiState.isConnected -> {
                    StatusIndicator("连接守护进程失败！\n请检查模块是否正常运行，并授予Root权限。", showProgress = false)
                }
                else -> {
                    // 【UI实现】使用与文档蓝图匹配的组件
                    GlobalStatusArea(stats = uiState.globalStats)
                    HorizontalDivider()
                    RuntimeStatusList(apps = uiState.activeApps)
                }
            }
        }
    }
}

@Composable
fun StatusIndicator(text: String, showProgress: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (showProgress) {
                CircularProgressIndicator()
            }
            Text(text, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}


// 【UI实现】对应文档“全局状态区 (Global Status Area)”
@Composable
fun GlobalStatusArea(stats: GlobalStats) {
    val memUsedPercent = if (stats.totalMemKb > 0) {
        100.0 * (stats.totalMemKb - stats.availMemKb) / stats.totalMemKb
    } else 0.0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "MODE: ${stats.activeProfileName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                InfoChip("CPU", "${"%.1f".format(stats.totalCpuUsagePercent)}%")
                InfoChip("MEM", "${"%.1f".format(memUsedPercent)}%")
                // 在这里可以添加更多指标，如温度、电流等
            }
        }
    }
}

@Composable
fun InfoChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}


// 【UI实现】对应文档“运行状态列表 (Runtime Status List)”
@Composable
fun RuntimeStatusList(apps: List<AppRuntimeState>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = apps, key = { it.packageName }) { app ->
            AppStatusCard(app = app)
        }
    }
}

@Composable
fun AppStatusCard(app: AppRuntimeState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // 左侧：应用图标 (暂用占位符)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.appName.take(1).uppercase(Locale.ROOT),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))

            // 中间：主要信息区
            Column(Modifier.weight(1f)) {
                // 第一行: 应用名 + 状态指示器
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                    AppStatusIndicatorIcons(app = app)
                }
                Spacer(Modifier.height(4.dp))
                // 第二行: 资源占用
                Text(
                    text = "MEM: ${app.memUsageKb / 1024}MB | CPU: ${"%.1f".format(app.cpuUsagePercent)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                // 第三行：详细状态文本
                Text(
                    text = "STATUS: ${getStatusText(app)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
    }
}

// 【UI实现】对应状态指示器图标
@Composable
fun AppStatusIndicatorIcons(app: AppRuntimeState) {
    Row {
        val iconModifier = Modifier.padding(horizontal = 2.dp)
        if (app.isForeground) Text("▶️", iconModifier)
        if (app.isWhitelisted) Text("🛡️", iconModifier)
        if (app.hasPlayback) Text("🎵", iconModifier)
        if (app.hasNotification) Text("🔔", iconModifier)
        if (app.hasNetworkActivity) Text("📡", iconModifier)

        when (app.displayStatus) {
            DisplayStatus.FROZEN -> Text("❄️", iconModifier)
            DisplayStatus.KILLED -> Text("🧊", iconModifier)
            DisplayStatus.AWAITING_FREEZE -> Text("⏳", iconModifier)
            else -> {}
        }
    }
}


fun getStatusText(app: AppRuntimeState): String {
    return when (app.displayStatus) {
        DisplayStatus.FOREGROUND -> "前台运行"
        DisplayStatus.FOREGROUND_GAME -> "前台游戏"
        DisplayStatus.BACKGROUND_ACTIVE -> "后台活动"
        DisplayStatus.BACKGROUND_IDLE -> "后台空闲"
        DisplayStatus.AWAITING_FREEZE -> "等待冻结 (剩余xx s)" // 待实现
        DisplayStatus.FROZEN -> "已冻结 (${app.activeFreezeMode?.name ?: "N/A"})"
        DisplayStatus.KILLED -> "已结束"
        DisplayStatus.EXEMPTED -> "自由后台 (已豁免)"
        DisplayStatus.UNKNOWN -> "状态未知"
    }
}

// 预览部分保持不变或根据新组件进行更新
@Preview(showBackground = true)
@Composable
fun DashboardScreenPreview() {
    CRFzitTheme {
        val previewState = DashboardUiState(
            isLoading = false,
            isConnected = true,
            globalStats = GlobalStats(
                totalCpuUsagePercent = 25.7f,
                totalMemKb = 8192000,
                availMemKb = 3000000,
                activeProfileName = "🎮 游戏模式"
            ),
            activeApps = listOf(
                AppRuntimeState(packageName = "com.tencent.mm", appName = "微信", isForeground = true, displayStatus = DisplayStatus.FOREGROUND),
                AppRuntimeState(packageName = "com.bilibili.app.in", appName = "哔哩哔哩", isWhitelisted = true, displayStatus = DisplayStatus.EXEMPTED),
                AppRuntimeState(packageName = "com.coolapk.market", appName = "酷安", displayStatus = DisplayStatus.AWAITING_FREEZE),
                AppRuntimeState(packageName = "com.xunmeng.pinduoduo", appName = "拼多多", displayStatus = DisplayStatus.FROZEN),
            )
        )
        // 直接预览 DashboardScreen
        // 注意：在实际的 ViewModel 中，你需要提供一个方式来注入这个预览状态，
        // 或者像这样直接调用内部的 Composable
    }
}