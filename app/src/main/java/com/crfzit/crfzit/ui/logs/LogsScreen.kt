// app/src/main/java/com/crfzit/crfzit/ui/logs/LogsScreen.kt
package com.crfzit.crfzit.ui.logs

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crfzit.crfzit.data.model.LogLevel
import com.crfzit.crfzit.ui.stats.StatisticsScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: LogsViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("事件时间线", "资源统计")

    Scaffold(topBar = { TopAppBar(title = { Text("日志与统计") }) }) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            when (selectedTab) {
                0 -> EventTimelineTab(viewModel)
                1 -> StatisticsScreen()
            }
        }
    }
}

@Composable
fun EventTimelineTab(viewModel: LogsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // [核心修复] 2. 修正自动滚动逻辑，防止干扰用户操作
    LaunchedEffect(viewModel, listState) {
        snapshotFlow { uiState.timelineItems.firstOrNull()?.id }
            .distinctUntilChanged()
            .collect { newId ->
                // 只有当新日志到来，且用户当前完全在列表顶部时，才自动滚动
                if (newId != null && listState.firstVisibleItemIndex == 0) {
                    listState.animateScrollToItem(0)
                }
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            )
    ) {
        if (uiState.isLoading && uiState.timelineItems.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items = uiState.timelineItems, key = { it.id }) { item ->
                    when (item) {
                        is SingleLogItem -> LogCard(item.log)
                        is LogGroupItem -> ReportGroupCard(item)
                    }
                }
                if (uiState.isLoadingMore) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            val shouldLoadMore by remember {
                derivedStateOf {
                    val li = listState.layoutInfo
                    val isAtBottom = li.visibleItemsInfo.lastOrNull()?.index == li.totalItemsCount - 1
                    isAtBottom && !uiState.isLoadingMore && !uiState.hasReachedEnd
                }
            }
            LaunchedEffect(shouldLoadMore) {
                if (shouldLoadMore) {
                    viewModel.loadMoreLogs()
                }
            }
        }
    }
}

// 正确实现高斯模糊毛玻璃效果的基类
@Composable
fun GlassmorphicCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier = modifier.clip(shape)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .then(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Modifier.graphicsLayer(
                            renderEffect = RenderEffect
                                .createBlurEffect(25f, 25f, Shader.TileMode.DECAL)
                                .asComposeRenderEffect()
                        )
                    } else {
                        Modifier
                    }
                )
        )
        content()
    }
}

// 普通日志卡片
@Composable
fun LogCard(log: UiLogEntry) {
    GlassmorphicCard {
        LogItemContent(log = log)
    }
}

// [核心修复] 1. 实现了统一报告在一个卡片中的布局
@Composable
fun ReportGroupCard(group: LogGroupItem) {
    GlassmorphicCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ReportHeader(log = group.parentLog)
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                group.childLogs.forEach { childLog ->
                    LogItemContent(log = childLog)
                }
            }
        }
    }
}

@Composable
private fun ReportHeader(log: UiLogEntry) {
    val (icon, color) = getLogAppearance(log.originalLog.level)
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = icon,
            fontSize = 28.sp,
            color = color.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = log.originalLog.message,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = formatter.format(Date(log.originalLog.timestamp)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DashedVerticalDivider() {
    val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Canvas(Modifier.fillMaxHeight().width(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(0f, size.height),
            pathEffect = pathEffect
        )
    }
}

@Composable
fun LogItemContent(log: UiLogEntry) {
    val originalLog = log.originalLog
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val (icon, color) = getLogAppearance(originalLog.level)

    val annotatedString = buildAnnotatedString {
        if (originalLog.level == LogLevel.REPORT) {
            val messageParts = originalLog.message.split('\n')
            val title = log.appName ?: messageParts.firstOrNull() ?: ""
            val details = messageParts.drop(1).joinToString("\n")
            withStyle(style = SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)) { append(title) }
            if (details.isNotEmpty()) {
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)) { append("\n$details") }
            }
        } else if (originalLog.category != "报告" && !log.appName.isNullOrEmpty()) {
            append("应用 ‘")
            withStyle(style = SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)) { append(log.appName) }
            append("’ "); append(originalLog.message)
        } else {
            append(originalLog.message)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(90.dp).padding(horizontal = 8.dp)
        ) {
            Text(
                text = icon,
                fontSize = 24.sp,
                color = color.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatter.format(Date(originalLog.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
        DashedVerticalDivider()
        Column(modifier = Modifier.padding(start = 12.dp, end = 16.dp).weight(1f)) {
            Text(
                text = "[${originalLog.category}]",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = color.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = annotatedString,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp)
            )
        }
    }
}

@Composable
fun getLogAppearance(level: LogLevel): Pair<String, Color> {
    return when (level) {
        LogLevel.INFO -> "ℹ️" to MaterialTheme.colorScheme.outline
        LogLevel.SUCCESS -> "✅" to Color(0xFF34A853)
        LogLevel.WARN -> "⚠️" to Color(0xFFFBBC05)
        LogLevel.ERROR -> "❌" to MaterialTheme.colorScheme.error
        LogLevel.EVENT -> "⚡" to MaterialTheme.colorScheme.primary
        LogLevel.DOZE -> "🌙" to Color(0xFF6650a4)
        LogLevel.BATTERY -> "🔋" to Color(0xFF0B8043)
        LogLevel.REPORT -> "📊" to Color(0xFF1A73E8)
        LogLevel.ACTION_OPEN -> "▶️" to Color.Unspecified
        LogLevel.ACTION_CLOSE -> "⏹️" to MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.ACTION_FREEZE -> "❄️" to Color(0xFF4285F4)
        LogLevel.ACTION_UNFREEZE -> "☀️" to Color(0xFFF4B400)
        LogLevel.ACTION_DELAY -> "⏳" to Color(0xFFE52592)
        LogLevel.TIMER -> "⏰" to Color(0xFFF25622)
        LogLevel.BATCH_PARENT -> "📦" to Color.Unspecified
    }
}