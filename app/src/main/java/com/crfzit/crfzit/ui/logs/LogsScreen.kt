// app/src/main/java/com/crfzit/crfzit/ui/logs/LogsScreen.kt
package com.crfzit.crfzit.ui.logs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crfzit.crfzit.data.model.LogLevel
import com.crfzit.crfzit.ui.stats.StatisticsScreen
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: LogsViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("事件时间线", "资源统计")

    Scaffold(
        topBar = { TopAppBar(title = { Text("日志与统计") }) }
    ) { padding ->
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

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.isLoading && uiState.logs.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(
                    items = uiState.logs,
                    key = { _, log ->
                        // 使用更多信息确保Key的唯一性
                        "${log.originalLog.timestamp}-${log.originalLog.message}-${log.originalLog.packageName}-${log.originalLog.user_id}"
                    }
                ) { _, log ->
                    LogItem(log = log)
                }

                if (uiState.isLoadingMore) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            val layoutInfo = listState.layoutInfo
            val shouldLoadMore = remember(layoutInfo) {
                derivedStateOf {
                    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                    // 触发加载的阈值可以调大一些，比如最后10个
                    lastVisibleItem != null && lastVisibleItem.index >= layoutInfo.totalItemsCount - 10
                }
            }

            LaunchedEffect(shouldLoadMore.value) {
                if (shouldLoadMore.value) {
                    viewModel.loadMoreLogs()
                }
            }
        }
    }
}

@Composable
fun LogItem(log: UiLogEntry) {
    val originalLog = log.originalLog
    
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val (icon, color) = getLogAppearance(originalLog.level)
    
    val annotatedString = buildAnnotatedString {
        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
            append(formatter.format(Date(originalLog.timestamp)))
        }
        append(" ")
        withStyle(style = SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
            append("$icon[${originalLog.category}]")
        }
        append(" ")

        // [核心修改] ViewModel已经处理好了应用名和分身标识
        // 这里只需要判断是否需要显示应用名即可
        if (originalLog.category != "报告" && !log.appName.isNullOrEmpty()) {
            append("应用 ‘")
            withStyle(style = SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)) {
                append(log.appName)
            }
            append("’ ")
        }
        
        append(originalLog.message)
    }

    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
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