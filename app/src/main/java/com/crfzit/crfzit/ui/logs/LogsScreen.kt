// app/src/main/java/com/crfzit/crfzit/ui/logs/LogsScreen.kt
package com.crfzit.crfzit.ui.logs

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crfzit.crfzit.data.model.LogEntry
import com.crfzit.crfzit.data.model.LogEventType
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class LogsViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LogsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LogsViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    viewModel: LogsViewModel = viewModel(
        factory = LogsViewModelFactory(LocalContext.current.applicationContext as Application)
    )
) {
    val uiState by viewModel.uiState.collectAsState()
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
                0 -> EventTimeline(
                    state = uiState,
                    onLoadMore = { viewModel.loadMoreLogs() }
                )
                1 -> ResourceStatistics()
            }
        }
    }
}

@Composable
fun EventTimeline(state: LogsUiState, onLoadMore: () -> Unit) {
    val listState = rememberLazyListState()
    
    val reachedBottom: Boolean by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            if (lastVisibleItem == null || listState.layoutInfo.totalItemsCount == 0) {
                false
            } else {
                lastVisibleItem.index == listState.layoutInfo.totalItemsCount - 1
            }
        }
    }

    LaunchedEffect(reachedBottom) {
        if (reachedBottom && state.canLoadMore && !state.isLoading) {
            onLoadMore()
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (state.isLoading && state.logs.isEmpty()) {
             CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                reverseLayout = false
            ) {
                items(state.logs, key = { it.timestamp.toString() + it.payload.toString() }) { log ->
                    LogItem(log)
                }
                
                if (state.isLoading) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogItem(log: LogEntry) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    
    val timestamp = "[${formatter.format(Date(log.timestamp))}]"
    val content = buildAnnotatedString {
        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.outline)) {
            append(timestamp)
        }
        append(" | ")
        
        when (log.eventType) {
            LogEventType.APP_FROZEN -> {
                val appName = log.payload["app_name"] as? String ?: "未知应用"
                val pidCount = (log.payload["pid_count"] as? Number)?.toInt()
                val sessionDuration = (log.payload["session_duration_s"] as? Number)?.toLong()
                val cumulativeDuration = (log.payload["cumulative_duration_s"] as? Number)?.toLong()

                withStyle(style = SpanStyle(color = Color(0xFF1A73E8), fontWeight = FontWeight.Bold)) { append("[❄️冻结]") }
                append(" | ")
                if (pidCount != null) {
                    append("[进程: $pidCount] | ")
                }
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("[$appName]") }
                append(" 已冻结")
                if(sessionDuration != null && cumulativeDuration != null) {
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.outline)) {
                        append(" (运行时长: ${formatDuration(sessionDuration)}, 累计: ${formatDuration(cumulativeDuration)})")
                    }
                }
            }
            LogEventType.APP_UNFROZEN -> {
                val appName = log.payload["app_name"] as? String ?: "未知应用"
                val reason = log.payload["reason"] as? String
                withStyle(style = SpanStyle(color = Color(0xFF34A853), fontWeight = FontWeight.Bold)) { append("[☀️解冻]") }
                append(" | ")
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("[$appName]") }
                append(" 已解冻")
                if (reason != null) {
                     withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.outline)) {
                        append(" (原因: $reason)")
                    }
                }
            }
            LogEventType.APP_FOREGROUND -> {
                 val appName = log.payload["app_name"] as? String ?: "未知应用"
                 withStyle(style = SpanStyle(color = Color(0xFF34A853), fontWeight = FontWeight.Bold)) { append("[▶️打开]") }
                 append(" | ")
                 withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("[$appName]") }
                 append(" 已到前台")
            }
            LogEventType.APP_STOP -> {
                val appName = log.payload["app_name"] as? String ?: "未知应用"
                val sessionDuration = (log.payload["session_duration_s"] as? Number)?.toLong()
                val cumulativeDuration = (log.payload["cumulative_duration_s"] as? Number)?.toLong()
                withStyle(style = SpanStyle(color = Color.Gray, fontWeight = FontWeight.Bold)) { append("[⏹️关闭]") }
                append(" | ")
                 withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("[$appName]") }
                 append(" 已结束")
                 if(sessionDuration != null && cumulativeDuration != null) {
                     withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.outline)) {
                        append(" (运行时长: ${formatDuration(sessionDuration)}, 累计: ${formatDuration(cumulativeDuration)})")
                    }
                }
            }
            LogEventType.DAEMON_START -> {
                withStyle(style = SpanStyle(color = Color.Magenta, fontWeight = FontWeight.Bold)) { append("[⚙️系统]") }
                append(" | ")
                append(log.payload["message"] as? String ?: "守护进程已启动")
            }
            else -> {
                withStyle(style = SpanStyle(color = Color.DarkGray, fontWeight = FontWeight.Bold)) { append("[ℹ️信息]") }
                append(" | ")
                val message = (log.payload["message"] as? String) ?: log.payload.toString()
                append(message)
            }
        }
    }
    
    Text(
        text = content,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth()
    )
}

fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds < 0) return "N/A"
    val hours = TimeUnit.SECONDS.toHours(totalSeconds)
    val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> String.format("%d时%d分%d秒", hours, minutes, seconds)
        minutes > 0 -> String.format("%d分%d秒", minutes, seconds)
        else -> String.format("%d秒", seconds)
    }
}

@Composable
fun ResourceStatistics() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("资源统计图表 (待实现)")
    }
}