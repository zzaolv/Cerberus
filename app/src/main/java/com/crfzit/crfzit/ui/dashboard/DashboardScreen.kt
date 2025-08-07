// app/src/main/java/com/crfzit/crfzit/ui/dashboard/DashboardScreen.kt
package com.crfzit.crfzit.ui.dashboard

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.crfzit.crfzit.R
import com.crfzit.crfzit.coil.AppIcon
import com.crfzit.crfzit.data.model.AppRuntimeState
import com.crfzit.crfzit.data.model.GlobalStats
import com.crfzit.crfzit.data.system.NetworkSpeed
import com.crfzit.crfzit.ui.icons.AppIcons
import com.crfzit.crfzit.ui.theme.CRFzitTheme
import java.util.*

// ... (DashboardScreen 和其他大部分函数保持不变) ...
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showMenu by remember { mutableStateOf(false) }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = uiState.isRefreshing,
        onRefresh = { viewModel.refresh() }
    )

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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pullRefresh(pullRefreshState)
                ) {
                    DashboardContent(
                        globalStats = uiState.globalStats,
                        networkSpeed = uiState.networkSpeed,
                        apps = uiState.apps
                    )

                    PullRefreshIndicator(
                        refreshing = uiState.isRefreshing,
                        state = pullRefreshState,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
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
fun AppRuntimeCard(app: UiAppRuntime) {
    val state = app.runtimeState
    Card {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(AppIcon(state.packageName))
                        .size(73)
                        .bitmapConfig(Bitmap.Config.RGB_565)
                        .placeholder(R.drawable.ic_launcher_foreground)
                        .error(R.drawable.ic_launcher_foreground)
                        .build()
                ),
                contentDescription = app.appName,
                modifier = Modifier.size(48.dp)
            )
            
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.appName,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (app.userId != 0) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            painter = painterResource(id = R.drawable.ic_clone),
                            contentDescription = "分身应用 (User ${app.userId})",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    AppStatusIcons(state = state)
                }
                
                val resourceText = buildAnnotatedString {
                    append("MEM: ${formatMemory(state.memUsageKb)}")
                    if (state.swapUsageKb > 1024) {
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
                    text = "状态：${formatStatus(state)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
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
    
    val downSpeed = formatSpeed(speed.downloadSpeedBps)
    val upSpeed = formatSpeed(speed.uploadSpeedBps)

    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = "系统状态",
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
fun LottieStatusIcon(assetName: String, modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.Asset("lottie/$assetName")
    )
    LottieAnimation(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        modifier = modifier.size(24.dp) 
    )
}

// [核心修正] 重构 AppStatusIcons 的逻辑，确保所有状态都能正确显示
@Composable
fun AppStatusIcons(state: AppRuntimeState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // 添加一个最小宽度，防止在状态切换时布局跳动
        modifier = Modifier.defaultMinSize(minHeight = 24.dp) 
    ) {
        val iconModifier = Modifier.padding(horizontal = 1.dp)

        // 1. 最高优先级：冻结状态 (静态图标)
        // 这个判断现在是正确的，直接检查后端传来的文本状态
        if (state.displayStatus.uppercase().contains("FROZEN")) {
            Text("❄️", iconModifier.size(22.dp))
            return@Row // 一旦冻结，不再显示任何其他状态
        }

        // 2. 独立状态：前台
        // 无论是否豁免，只要是前台就显示
        if (state.isForeground) {
            LottieStatusIcon("anim_foreground.json", modifier = iconModifier)
        }

        // 3. 独立状态：豁免
        // 无论前台后台，只要是豁免就显示
        if (state.isWhitelisted) {
            LottieStatusIcon("anim_exempted.json", modifier = iconModifier)
        }

        // 4. 附加活动状态 (可以与其他状态共存)
        if (state.isPlayingAudio) {
            LottieStatusIcon("anim_audio.json", modifier = iconModifier)
        }
        if (state.isUsingLocation) {
            LottieStatusIcon("anim_location.json", modifier = iconModifier)
        }
        if (state.hasHighNetworkUsage) {
            LottieStatusIcon("anim_network.json", modifier = iconModifier)
        }

        // 5. 补充状态：常规后台
        // 仅当应用处于后台、且不是豁免状态、且没有任何特殊活动时显示
        val isBackground = !state.isForeground
        val isNotWhitelisted = !state.isWhitelisted
        val hasNoSpecialActivity = !state.isPlayingAudio && !state.isUsingLocation && !state.hasHighNetworkUsage
        
        if (isBackground && isNotWhitelisted && hasNoSpecialActivity) {
            LottieStatusIcon("anim_background.json", modifier = iconModifier)
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

private fun formatSpeed(bitsPerSecond: Long): Pair<String, String> {
    if (bitsPerSecond < 50000) return Pair("0.0", "Kbps")
    return when {
        bitsPerSecond < 1_000_000 -> Pair("%.1f".format(Locale.US, bitsPerSecond / 1000.0), "Kbps")
        else -> Pair("%.1f".format(Locale.US, bitsPerSecond / 1_000_000.0), "Mbps")
    }
}

private fun formatStatus(state: AppRuntimeState): String {
    val status = state.displayStatus.uppercase()
    return when {
        status.startsWith("PENDING_FREEZE") -> {
            val time = status.substringAfter("(").substringBefore("s")
            "等待冻结 (${time}s)"
        }
        status.startsWith("OBSERVING") -> {
            val time = status.substringAfter("(").substringBefore("s")
            "后台观察中 (${time}s)"
        }
        status == "STOPPED" -> "未运行"
        status.contains("FROZEN") -> "已冻结"
        status == "FOREGROUND" -> "前台运行"
        status == "EXEMPTED_BACKGROUND" -> "后台运行 (已豁免)"
        status == "BACKGROUND" -> "后台运行"
        else -> state.displayStatus
    }
}

@Preview(showBackground = true)
@Composable
fun DashboardPreview() {
    CRFzitTheme {
        DashboardContent(
            globalStats = GlobalStats(),
            networkSpeed = NetworkSpeed(),
            apps = emptyList()
        )
    }
}