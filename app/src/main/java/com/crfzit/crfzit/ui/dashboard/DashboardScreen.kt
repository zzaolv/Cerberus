// app/src/main/java/com/crfzit/crfzit/ui/dashboard/DashboardScreen.kt
package com.crfzit.crfzit.ui.dashboard

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.crfzit.crfzit.data.model.AppRuntimeState
import com.crfzit.crfzit.data.model.GlobalStats
import com.crfzit.crfzit.data.system.NetworkSpeed
import com.crfzit.crfzit.ui.icons.AppIcons
import com.crfzit.crfzit.ui.theme.CRFzitTheme
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("主页") },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
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
            )
        }
    ) { paddingValues ->
        Crossfade(
            targetState = uiState.isConnected,
            modifier = Modifier.padding(paddingValues),
            label = "ConnectionState"
        ) { isConnected ->
            if (isConnected) {
                DashboardContent(
                    globalStats = uiState.globalStats,
                    networkSpeed = uiState.networkSpeed,
                    apps = uiState.apps
                )
            } else {
                ConnectionLoadingIndicator()
            }
        }
    }
}

@Composable
fun DashboardContent(
    globalStats: GlobalStats,
    networkSpeed: NetworkSpeed,
    apps: List<UiAppRuntime>
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            GlobalStatusArea(stats = globalStats, speed = networkSpeed)
        }
        item {
            Text(
                text = "运行状态列表",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }
        items(apps, key = { "${it.runtimeState.packageName}-${it.runtimeState.userId}" }) { app ->
            AppRuntimeCard(app = app)
        }
    }
}

@Composable
fun GlobalStatusArea(stats: GlobalStats, speed: NetworkSpeed) {
    val memUsedPercent = if (stats.totalMemKb > 0) {
        (stats.totalMemKb - stats.availMemKb).toFloat() / stats.totalMemKb
    } else 0f
    
    val swapUsedPercent = if (stats.swapTotalKb > 0) {
        (stats.swapTotalKb - stats.swapFreeKb).toFloat() / stats.swapTotalKb
    } else 0f
    
    val cpuUsedPercent = stats.totalCpuUsagePercent / 100f
    
    // [FIX 2.1] formatSpeed现在会过滤低速率
    val downSpeed = formatSpeed(speed.downloadSpeedBps)
    val upSpeed = formatSpeed(speed.uploadSpeedBps)

    Column(modifier = Modifier.padding(bottom = 8.dp)) {
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
                    icon = AppIcons.Memory
                )
            }
            item {
                StatusGridItem(
                    label = "内存 (MEM)",
                    value = formatMemory(stats.totalMemKb - stats.availMemKb),
                    progress = memUsedPercent,
                    icon = AppIcons.SdStorage
                )
            }
            item {
                 StatusGridItem(
                    label = "交换 (SWAP)",
                    value = formatMemory(stats.swapTotalKb - stats.swapFreeKb),
                    progress = swapUsedPercent,
                    icon = AppIcons.SwapHoriz
                )
            }
            item {
                StatusGridItem(
                    label = "网络",
                    value = "↓${downSpeed.first} | ↑${upSpeed.first}",
                    subValue = "${downSpeed.second} / ${upSpeed.second}",
                    icon = AppIcons.Wifi
                )
            }
        }
    }
}

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
                    progress = { progress ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                )
            }
        }
    }
}


@Composable
fun AppRuntimeCard(app: UiAppRuntime) {
    val state = app.runtimeState
    Card {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // [FIX 1.1] Image现在使用ViewModel中提供的图标
            Image(
                painter = rememberAsyncImagePainter(
                    ImageRequest.Builder(LocalContext.current)
                        .data(app.icon) // 使用合并后的icon
                        .placeholder(android.R.drawable.sym_def_app_icon)
                        .error(android.R.drawable.sym_def_app_icon)
                        .crossfade(true).build()
                ),
                contentDescription = app.appName, // 使用合并后的appName
                modifier = Modifier.size(48.dp)
            )
            
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.appName, // 使用合并后的appName
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    AppStatusIcons(state = state)
                }
                
                // [FIX 1.4] 动态构建资源占用字符串
                val resourceText = buildAnnotatedString {
                    append("MEM: ${formatMemory(state.memUsageKb)}")
                    if (state.swapUsageKb > 1024) { // 只在SWAP大于1KB时显示
                        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))) {
                            append(" (+${formatMemory(state.swapUsageKb)} S)")
                        }
                    }
                    append(" | CPU: ${"%.1f".format(state.cpuUsagePercent)}%")
                }

                Text(
                    text = resourceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "STATUS: ${formatStatus(state)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AppStatusIcons(state: AppRuntimeState) {
    Row {
        val iconModifier = Modifier.padding(horizontal = 2.dp)
        if (state.isForeground) Text("▶️", iconModifier)
        if (state.isWhitelisted) Text("🛡️", iconModifier)
        if (state.hasPlayback) Text("🎵", iconModifier)
        if (state.hasNotification) Text("🔔", iconModifier)
        if (state.hasNetworkActivity) Text("📡", iconModifier)
        when (state.displayStatus.uppercase()) {
            "FROZEN" -> Text("❄️", iconModifier)
            "KILLED" -> Text("🧊", iconModifier)
            "AWAITING_FREEZE" -> Text("⏳", iconModifier)
            else -> {}
        }
    }
}

@Composable
fun ConnectionLoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("正在连接到守护进程...")
        }
    }
}

private fun formatMemory(kb: Long): String {
    if (kb <= 0) return "0 KB"
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.1f GB".format(Locale.US, gb)
        mb >= 1 -> "%.1f MB".format(Locale.US, mb)
        else -> "$kb KB"
    }
}

// [FIX 2.1] 修改 formatSpeed 函数以过滤低速率
private fun formatSpeed(bitsPerSecond: Long): Pair<String, String> {
    if (bitsPerSecond < 50000) return Pair("0.0", "Kbps")
    return when {
        bitsPerSecond < 1_000_000 -> Pair("%.1f".format(Locale.US, bitsPerSecond / 1000.0), "Kbps")
        else -> Pair("%.1f".format(Locale.US, bitsPerSecond / 1_000_000.0), "Mbps")
    }
}

private fun formatStatus(state: AppRuntimeState): String {
    return when (state.displayStatus.uppercase()) {
        "AWAITING_FREEZE" -> "等待冻结 (${state.pendingFreezeSec}s)"
        else -> state.displayStatus.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}

@Preview(showBackground = true)
@Composable
fun DashboardPreview() {
    CRFzitTheme {
        DashboardContent(
            globalStats = GlobalStats(activeProfileName = "🎮 游戏模式"),
            networkSpeed = NetworkSpeed(),
            apps = listOf(
                UiAppRuntime(
                    AppRuntimeState(packageName = "com.example.app", appName = "示例应用", isForeground = true, displayStatus = "FOREGROUND"),
                    appName = "示例应用", icon = null, isSystem = false
                )
            )
        )
    }
}