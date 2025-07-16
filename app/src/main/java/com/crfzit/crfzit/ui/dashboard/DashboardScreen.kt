// app/src/main/java/com/crfzit/crfzit/ui/dashboard/DashboardScreen.kt
package com.crfzit.crfzit.ui.dashboard

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
// 【核心修复】导入 CircleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.crfzit.crfzit.R
import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.AppRuntimeState
import com.crfzit.crfzit.data.model.DisplayStatus
import com.crfzit.crfzit.data.model.GlobalStats
import com.crfzit.crfzit.data.model.Policy
import com.crfzit.crfzit.data.system.NetworkSpeed
import com.crfzit.crfzit.ui.theme.CRFzitTheme
// 【核心修复】导入我们自己的图标
import com.crfzit.crfzit.ui.icons.AppIcons
import java.util.Locale 

// 内存格式化工具函数
fun formatMemory(kb: Long): String {
    if (kb <= 0) return "0 KB"
    if (kb < 1024) return "${kb} KB"
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.1f GB".format(Locale.US, gb)
        else -> "%.1f MB".format(Locale.US, mb)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(factory = DashboardViewModelFactory(LocalContext.current.applicationContext as Application))
) {
    val uiState by viewModel.uiState.collectAsState()
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        // 【UI美化】使用更紧凑的居中顶栏
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Cerberus") },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多选项")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (uiState.showSystemApps) "隐藏系统应用" else "显示系统应用") },
                                onClick = {
                                    viewModel.onShowSystemAppsChanged(!uiState.showSystemApps)
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        DashboardContent(
            uiState = uiState,
            modifier = Modifier.padding(paddingValues)
        )
    }
}

class DashboardViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DashboardViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}


@Composable
fun DashboardContent(uiState: DashboardUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        when {
            uiState.isLoading -> {
                StatusIndicator("正在连接守护进程...", showProgress = true)
            }
            !uiState.isConnected -> {
                StatusIndicator("连接失败！\n请检查模块是否正常运行。", showProgress = false)
            }
            else -> {
                GlobalStatusArea(stats = uiState.globalStats, speed = uiState.networkSpeed)
                HorizontalDivider()
                RuntimeStatusList(apps = uiState.displayedApps)
            }
        }
    }
}

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

// 【UI美化】完全重构全局状态区域
@Composable
fun GlobalStatusArea(stats: GlobalStats, speed: NetworkSpeed) {
    val memUsedPercent = if (stats.totalMemKb > 0) {
        (stats.totalMemKb - stats.availMemKb).toFloat() / stats.totalMemKb
    } else 0f
    
    val swapUsedPercent = if (stats.swapTotalKb > 0) {
        (stats.swapTotalKb - stats.swapFreeKb).toFloat() / stats.swapTotalKb
    } else 0f
    
    val cpuUsedPercent = stats.totalCpuUsagePercent / 100f
    
    val downSpeed = formatSpeed(speed.downloadSpeedBps)
    val upSpeed = formatSpeed(speed.uploadSpeedBps)

    Column(modifier = Modifier.padding(12.dp)) {
        Text(
            text = "MODE: ${stats.activeProfileName}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 12.dp)
        )
        
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.height(180.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false
        ) {
            item {
                StatusGridItem(
                    label = "CPU",
                    value = "%.1f".format(Locale.US, stats.totalCpuUsagePercent) + "%",
                    progress = cpuUsedPercent,
                    // 【核心修复】使用我们自己的图标
                    icon = AppIcons.Memory
                )
            }
            item {
                StatusGridItem(
                    label = "内存 (MEM)",
                    value = formatMemory(stats.totalMemKb - stats.availMemKb),
                    progress = memUsedPercent,
                    // 【核心修复】使用我们自己的图标
                    icon = AppIcons.SdStorage
                )
            }
            item {
                 StatusGridItem(
                    label = "交换 (SWAP)",
                    value = formatMemory(stats.swapTotalKb - stats.swapFreeKb),
                    progress = swapUsedPercent,
                    // 【核心修复】使用我们自己的图标
                    icon = AppIcons.SwapHoriz
                )
            }
            item {
                StatusGridItem(
                    label = "网络",
                    value = "↓${downSpeed.first} | ↑${upSpeed.first}",
                    subValue = "${downSpeed.second} / ${upSpeed.second}",
                    // 【核心修复】使用我们自己的图标
                    icon = AppIcons.Wifi
                )
            }
        }
    }
}

// 【UI美化】新的网格项组件
@Composable
fun StatusGridItem(
    label: String,
    value: String,
    subValue: String? = null,
    progress: Float? = null,
    icon: ImageVector
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(12.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.weight(1f))
            if (subValue != null) {
                 Text(value, style = MaterialTheme.typography.titleMedium)
                 Text(subValue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            if (progress != null) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    // 【核心修复】使用 clip(CircleShape)
                    modifier = Modifier.fillMaxWidth().clip(CircleShape)
                )
            }
        }
    }
}


@Composable
fun RuntimeStatusList(apps: List<UiApp>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = apps, key = { "${it.runtimeState.packageName}-${it.runtimeState.userId}" }) { app ->
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
                contentDescription = "${app.appInfo?.appName} icon",
                modifier = Modifier.size(48.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val displayName = app.appInfo?.appName ?: app.runtimeState.packageName
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (app.runtimeState.userId != 0) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            painter = painterResource(id = R.drawable.ic_clone),
                            contentDescription = "分身应用 (User ${app.runtimeState.userId})",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    AppStatusIndicatorIcons(app = app.runtimeState)
                }

                val resourceText = buildAnnotatedString {
                    append("MEM: ${formatMemory(app.runtimeState.memUsageKb)}")
                    if (app.runtimeState.swapUsageKb > 1024) {
                        withStyle(style = SpanStyle(color = Color.Gray)) {
                            append(" (+${formatMemory(app.runtimeState.swapUsageKb)} S)")
                        }
                    }
                    append(" | CPU: ${"%.1f".format(Locale.US, app.runtimeState.cpuUsagePercent)}%")
                }

                Text(
                    text = resourceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

// 【低速率过滤】修改 formatSpeed 函数
fun formatSpeed(bitsPerSecond: Long): Pair<String, String> {
    // 如果速率低于 50 Kbps (50000 bps)，则直接显示为 0
    if (bitsPerSecond < 50000) return Pair("0.0", "Kbps")

    return when {
        bitsPerSecond < 1000 * 1000 -> Pair("%.1f".format(Locale.US, bitsPerSecond / 1000.0), "Kbps")
        bitsPerSecond < 1000 * 1000 * 1000 -> Pair("%.1f".format(Locale.US, bitsPerSecond / (1000.0 * 1000.0)), "Mbps")
        else -> Pair("%.1f".format(Locale.US, bitsPerSecond / (1000.0 * 1000.0 * 1000.0)), "Gbps")
    }
}

fun getStatusText(app: AppRuntimeState): String {
    return when (app.displayStatus) {
        DisplayStatus.STOPPED -> "未运行"
        DisplayStatus.FOREGROUND -> "前台运行"
        DisplayStatus.FOREGROUND_GAME -> "前台游戏"
        DisplayStatus.BACKGROUND_ACTIVE -> "后台活动"
        DisplayStatus.BACKGROUND_IDLE -> "后台运行 (缓存)"
        DisplayStatus.AWAITING_FREEZE -> "等待冻结中 (${app.pendingFreezeSec}s)"
        DisplayStatus.FROZEN -> "已冻结"
        DisplayStatus.KILLED -> "已结束"
        DisplayStatus.EXEMPTED -> "自由后台 (豁免)"
        DisplayStatus.UNKNOWN -> "状态未知"
    }
}

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
                swapTotalKb = 4096000,
                swapFreeKb = 2048000,
                activeProfileName = "🎮 游戏模式"
            ),
            networkSpeed = NetworkSpeed(downloadSpeedBps = 12_582_912, uploadSpeedBps = 1_310_720),
            displayedApps = listOf(
                 UiApp(
                    runtimeState = AppRuntimeState(
                        packageName = "com.tencent.mm", appName = "微信", userId = 0,
                        isForeground = true, memUsageKb = 512000, swapUsageKb = 128000,
                        displayStatus = DisplayStatus.FOREGROUND
                    ),
                    appInfo = AppInfo("com.tencent.mm", "微信", Policy.IMPORTANT, icon = null)
                ),
                UiApp(
                    runtimeState = AppRuntimeState(
                        packageName = "com.tencent.mm", appName = "微信", userId = 999,
                        memUsageKb = 256000, swapUsageKb = 0,
                        displayStatus = DisplayStatus.BACKGROUND_IDLE
                    ),
                    appInfo = AppInfo("com.tencent.mm", "微信 (分身)", Policy.IMPORTANT, icon = null)
                )
            )
        )
        DashboardContent(uiState = previewState)
    }
}