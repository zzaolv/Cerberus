package com.crfzit.crfzit.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider // <-- 已修正：使用 HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crfzit.crfzit.data.model.AppRuntimeState
import com.crfzit.crfzit.data.model.DisplayStatus
import com.crfzit.crfzit.data.model.FreezeMode
import com.crfzit.crfzit.data.model.GlobalStats
import com.crfzit.crfzit.ui.theme.CRFzitTheme
import java.util.Locale

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel()
) {
    // 正确使用 collectAsState，不会产生 linter 警告
    val uiState by viewModel.uiState.collectAsState()

    // 将状态传递给纯UI的可组合项，这是最佳实践
    DashboardContent(
        uiState = uiState
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardContent(uiState: DashboardUiState) {
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
                    GlobalStatsHeader(stats = uiState.globalStats)
                    HorizontalDivider() // <-- 已修正：使用 HorizontalDivider
                    ActiveAppsList(apps = uiState.activeApps)
                }
            }
        }
    }
}

@Composable
fun StatusIndicator(text: String, showProgress: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showProgress) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text(text, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}

@Composable
fun GlobalStatsHeader(stats: GlobalStats) {
    val memUsedGb = (stats.totalMemKb - stats.availMemKb) / (1024.0 * 1024.0)
    val memTotalGb = stats.totalMemKb / (1024.0 * 1024.0)
    val netDownMbps = stats.netDownSpeedBps / (1024.0 * 1024.0 * 8)
    val netUpKbps = stats.netUpSpeedBps / (1024.0 * 8)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "CPU: ${"%.1f".format(stats.totalCpuUsagePercent)}%", fontSize = 14.sp)
            Text(text = "MEM: ${"%.1f".format(memUsedGb)}/${"%.1f".format(memTotalGb)}G", fontSize = 14.sp)
            Text(text = "NET: ↓${"%.1f".format(netDownMbps)}MB/s ↑${"%.0f".format(netUpKbps)}KB/s", fontSize = 14.sp)
        }
        Text(text = "MODE: ${stats.activeProfileName}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ActiveAppsList(apps: List<AppRuntimeState>) {
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
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.appName.take(2).uppercase(Locale.ROOT),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                AppStatusIndicators(app = app)
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) // <-- 已修正
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "MEM: ${app.memUsageKb / 1024}MB | CPU: ${"%.1f".format(app.cpuUsagePercent)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = getStatusText(app),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun AppStatusIndicators(app: AppRuntimeState) {
    Row {
        if (app.isForeground) Text("▶️", modifier = Modifier.padding(horizontal = 2.dp))
        if (app.displayStatus == DisplayStatus.FOREGROUND_GAME) Text("🎮", modifier = Modifier.padding(horizontal = 2.dp))
        if (app.isWhitelisted) Text("🛡️", modifier = Modifier.padding(horizontal = 2.dp))
        if (app.hasPlayback) Text("🎵", modifier = Modifier.padding(horizontal = 2.dp))
        if (app.hasNotification) Text("🔔", modifier = Modifier.padding(horizontal = 2.dp))
        if (app.hasNetworkActivity) Text("📡", modifier = Modifier.padding(horizontal = 2.dp))

        when (app.displayStatus) {
            DisplayStatus.FROZEN -> Text("❄️", modifier = Modifier.padding(horizontal = 2.dp))
            DisplayStatus.KILLED -> Text("🧊", modifier = Modifier.padding(horizontal = 2.dp))
            DisplayStatus.AWAITING_FREEZE -> Text("⏳", modifier = Modifier.padding(horizontal = 2.dp))
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
        DisplayStatus.AWAITING_FREEZE -> "等待冻结"
        DisplayStatus.FROZEN -> "已冻结 (${app.activeFreezeMode?.name ?: "N/A"})"
        DisplayStatus.KILLED -> "已结束"
        DisplayStatus.EXEMPTED -> "自由后台"
        DisplayStatus.UNKNOWN -> "状态未知"
    }
}


// --- 预览函数区 ---
// 这里是正确的预览实现方式，不会导致任何编译错误或警告。

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
fun DashboardScreenConnectedPreview() {
    CRFzitTheme {
        val previewState = DashboardUiState(
            globalStats = GlobalStats(
                totalCpuUsagePercent = 15.7f,
                totalMemKb = 12000000,
                availMemKb = 4000000,
                netDownSpeedBps = 1234567,
                netUpSpeedBps = 56789,
                activeProfileName = "🎮 游戏模式"
            ),
            activeApps = listOf(
                AppRuntimeState(packageName = "com.tencent.mm", appName = "微信", isForeground = true, displayStatus = DisplayStatus.FOREGROUND, memUsageKb = 350 * 1024, cpuUsagePercent = 12.3f),
                AppRuntimeState(packageName = "com.netease.cloudmusic", appName = "网易云音乐", hasPlayback = true, isWhitelisted = true, displayStatus = DisplayStatus.EXEMPTED, memUsageKb = 180 * 1024),
                AppRuntimeState(packageName = "com.alibaba.taobao", appName = "淘宝", displayStatus = DisplayStatus.AWAITING_FREEZE, memUsageKb = 500 * 1024),
                AppRuntimeState(packageName = "com.xunmeng.pinduoduo", appName = "拼多多", displayStatus = DisplayStatus.FROZEN, activeFreezeMode = FreezeMode.CGROUP)
            ),
            isLoading = false,
            isConnected = true
        )
        // 直接将模拟状态传入内部 Content 函数进行预览
        DashboardContent(uiState = previewState)
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
fun DashboardScreenConnectingPreview() {
    CRFzitTheme {
        DashboardContent(uiState = DashboardUiState(isLoading = true, isConnected = false))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
fun DashboardScreenFailedPreview() {
    CRFzitTheme {
        DashboardContent(uiState = DashboardUiState(isLoading = false, isConnected = false))
    }
}