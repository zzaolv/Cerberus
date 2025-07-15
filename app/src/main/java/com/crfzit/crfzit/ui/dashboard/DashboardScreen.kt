// app/src/main/java/com/crfzit/crfzit/ui/dashboard/DashboardScreen.kt
package com.crfzit.crfzit.ui.dashboard

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.AppRuntimeState
import com.crfzit.crfzit.data.model.DisplayStatus
import com.crfzit.crfzit.data.model.GlobalStats
import com.crfzit.crfzit.data.model.Policy
import com.crfzit.crfzit.ui.theme.CRFzitTheme
import java.util.Locale

// 顶层 Composable，负责获取 ViewModel 和观察状态
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    // 使用 ViewModel Factory 来创建需要 Application Context 的 ViewModel
    viewModel: DashboardViewModel = viewModel(factory = DashboardViewModelFactory(LocalContext.current.applicationContext as Application))
) {
    val uiState by viewModel.uiState.collectAsState()

    // 将 UI 状态传递给纯展示的 Composable
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
        DashboardContent(
            uiState = uiState,
            modifier = Modifier.padding(paddingValues)
        )
    }
}

// ViewModel Factory，用于创建 AndroidViewModel
class DashboardViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DashboardViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// 纯展示的 Content Composable
@Composable
fun DashboardContent(uiState: DashboardUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        when {
            uiState.isLoading -> {
                StatusIndicator("正在连接守护进程并加载应用列表...", showProgress = true)
            }
            !uiState.isConnected -> {
                StatusIndicator("连接守护进程失败！\n请检查模块是否正常运行，并授予Root权限。", showProgress = false)
            }
            else -> {
                GlobalStatusArea(stats = uiState.globalStats)
                HorizontalDivider()
                RuntimeStatusList(apps = uiState.activeApps)
            }
        }
    }
}

// --- 以下是所有辅助函数 (Helper Composables) ---

@Composable
fun StatusIndicator(text: String, showProgress: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (showProgress) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            }
            Text(
                text = text,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun GlobalStatusArea(stats: GlobalStats) {
    val memUsedPercent = if (stats.totalMemKb > 0) {
        100.0 * (stats.totalMemKb - stats.availMemKb) / stats.totalMemKb
    } else 0.0

    // 【修改】使用驼峰式命名
    val downSpeed = formatSpeed(stats.netDownSpeedBps)
    val upSpeed = formatSpeed(stats.netUpSpeedBps)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "MODE: ${stats.activeProfileName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                InfoChip("CPU", "${"%.1f".format(stats.totalCpuUsagePercent)}", "%")
                InfoChip("MEM", "${"%.0f".format(memUsedPercent)}", "%")
                InfoChip("↓", downSpeed.first, downSpeed.second)
                InfoChip("↑", upSpeed.first, upSpeed.second)
            }
        }
    }
}

@Composable
fun InfoChip(label: String, value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, lineHeight = 30.sp)
        }
        Text(text = unit, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
    }
}


@Composable
fun RuntimeStatusList(apps: List<UiApp>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = apps, key = { it.runtimeState.packageName }) { app ->
            AppStatusCard(app = app)
        }
    }
}

@Composable
fun AppStatusCard(app: UiApp) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(app.appInfo?.icon)
                        .crossfade(true)
                        .build(),
                ),
                contentDescription = "${app.appInfo?.appName ?: app.runtimeState.packageName} icon",
                modifier = Modifier.size(48.dp)
            )
            
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.appInfo?.appName ?: app.runtimeState.packageName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false) // 避免挤占图标空间
                    )
                    Spacer(Modifier.width(8.dp))
                    AppStatusIndicatorIcons(app = app.runtimeState)
                }
                Text(
                    text = "MEM: ${app.runtimeState.memUsageKb / 1024}MB | CPU: ${"%.1f".format(app.runtimeState.cpuUsagePercent)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "STATUS: ${getStatusText(app.runtimeState)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
    }
}

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

// --- 以下是所有辅助函数 (Pure Functions) ---

fun formatSpeed(bitsPerSecond: Long): Pair<String, String> {
    return when {
        bitsPerSecond < 1000 -> Pair(bitsPerSecond.toString(), "bps")
        bitsPerSecond < 1000 * 1000 -> Pair("%.1f".format(Locale.US, bitsPerSecond / 1000.0), "Kbps")
        bitsPerSecond < 1000 * 1000 * 1000 -> Pair("%.1f".format(Locale.US, bitsPerSecond / (1000.0 * 1000.0)), "Mbps")
        else -> Pair("%.1f".format(Locale.US, bitsPerSecond / (1000.0 * 1000.0 * 1000.0)), "Gbps")
    }
}

fun getStatusText(app: AppRuntimeState): String {
    return when (app.displayStatus) {
        DisplayStatus.FOREGROUND -> "前台运行"
        DisplayStatus.FOREGROUND_GAME -> "前台游戏"
        DisplayStatus.BACKGROUND_ACTIVE -> "后台活动"
        DisplayStatus.BACKGROUND_IDLE -> "后台空闲"
        DisplayStatus.AWAITING_FREEZE -> "等待冻结中"
        DisplayStatus.FROZEN -> "已冻结 (CGROUP)"
        DisplayStatus.KILLED -> "已结束"
        DisplayStatus.EXEMPTED -> "自由后台 (已豁免)"
        DisplayStatus.UNKNOWN -> "状态未知"
    }
}


// --- 预览区 ---
@Preview(showBackground = true, widthDp = 360)
@Composable
fun DashboardContentPreview() {
    CRFzitTheme {
        val previewState = DashboardUiState(
            isLoading = false,
            isConnected = true,
            globalStats = GlobalStats(
                totalCpuUsagePercent = 25.7f,
                totalMemKb = 8192000,
                availMemKb = 3000000,
                // 【修改】使用驼峰式命名
                netDownSpeedBps = 12_582_912, // 12 Mbps
                netUpSpeedBps = 1_310_720,    // 1.3 Mbps
                activeProfileName = "🎮 游戏模式"
            ),
            activeApps = listOf(
                UiApp(
                    runtimeState = AppRuntimeState(packageName = "com.tencent.mm", appName = "微信", isForeground = true, displayStatus = DisplayStatus.FOREGROUND),
                    // 【修改】使用统一的 AppInfo，icon 为 null
                    appInfo = AppInfo("com.tencent.mm", "微信", Policy.IMPORTANT, icon = null)
                ),
                UiApp(
                    runtimeState = AppRuntimeState(packageName = "com.bilibili.app.in", appName = "哔哩哔哩", isWhitelisted = true, displayStatus = DisplayStatus.EXEMPTED),
                    appInfo = AppInfo("com.bilibili.app.in", "哔哩哔哩", Policy.STANDARD, icon = null)
                ),
                UiApp(
                    runtimeState = AppRuntimeState(packageName = "com.coolapk.market", appName = "酷安", displayStatus = DisplayStatus.AWAITING_FREEZE),
                    appInfo = AppInfo("com.coolapk.market", "酷安", Policy.STANDARD, icon = null)
                ),
                UiApp(
                    runtimeState = AppRuntimeState(packageName = "com.xunmeng.pinduoduo", appName = "拼多多", displayStatus = DisplayStatus.FROZEN),
                    appInfo = AppInfo("com.xunmeng.pinduoduo", "拼多多", Policy.STRICT, icon = null)
                ),
            )
        )
        DashboardContent(uiState = previewState)
    }
}