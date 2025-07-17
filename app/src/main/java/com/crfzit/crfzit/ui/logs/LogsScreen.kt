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
import androidx.compose.ui.text.AnnotatedString
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
    
    val content = buildLogContent(log = log, timestamp = timestamp)

    if (log.eventType == LogEventType.BATCH_OPERATION_START || log.eventType == LogEventType.DOZE_RESOURCE_REPORT) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(content.first, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            content.second?.forEach { subItem ->
                Text(subItem, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp))
            }
        }
    } else {
        Text(content.first, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
}

@Composable
private fun buildLogContent(log: LogEntry, timestamp: String): Pair<AnnotatedString, List<AnnotatedString>?> {
    var subItems: MutableList<AnnotatedString>? = null
    
    val header = buildAnnotatedString {
        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.outline)) { append(timestamp) }
        append(" | ")
    }

    val mainContent = buildAnnotatedString {
        append(header)
        val payload = log.payload
        when (log.eventType) {
            LogEventType.POWER_UPDATE, LogEventType.POWER_WARNING -> {
                val icon = if(log.eventType == LogEventType.POWER_WARNING) "⚡️警告" else "🔋电量"
                val color = if(log.eventType == LogEventType.POWER_WARNING) Color(0xFFEA4335) else Color(0xFF34A853)
                val capacity = (payload["capacity"] as? Number)?.toInt() ?: -1
                val temp = (payload["temperature"] as? Number)?.toFloat() ?: -1f
                val power = (payload["power_watt"] as? Number)?.toFloat() ?: -1f
                val consumption = (payload["consumption_percent"] as? Number)?.toInt()
                val duration = (payload["consumption_duration_min"] as? Number)?.toInt()

                withStyle(style = SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append("[$icon]") }
                append(" | [当前: $capacity%]")
                if(consumption != null && duration != null && duration > 0){
                    append(" | [消耗: $consumption% / ${duration}分钟]")
                }
                append(" | [功率: %.2fw]".format(power))
                append(" | [温度: %.1f°C]".format(temp))
            }

            LogEventType.DOZE_STATE_CHANGE -> {
                withStyle(style = SpanStyle(color = Color.Blue, fontWeight = FontWeight.Bold)) { append("[🌙Doze]") }
                val status = payload["status"] as? String ?: ""
                val debugInfo = payload["debug_info"] as? String ?: ""
                append(" | [状态: $status] | $debugInfo")
            }
            
            LogEventType.BATCH_OPERATION_START -> {
                withStyle(style = SpanStyle(color = Color(0xFF1A73E8), fontWeight = FontWeight.Bold)) { append("[❄️批量处理]") }
                append(" | ${payload["title"]}")
                
                subItems = mutableListOf()
                val actions = payload["actions"] as? List<Map<String, Any>> ?: emptyList()
                actions.forEach { action ->
                    subItems?.add(buildAnnotatedString {
                        append("| ")
                        val appName = action["app_name"] as? String ?: "N/A"
                        when(action["type"] as? String){
                            "network_block" -> {
                                withStyle(style = SpanStyle(color = Color.Red, fontWeight = FontWeight.Bold)) { append("[❌断网]") }
                                append(" | [$appName] 断网成功")
                            }
                            "freeze" -> {
                                val pCount = (action["pid_count"] as? Number)?.toInt() ?: 1
                                withStyle(style = SpanStyle(color = Color(0xFF1A73E8), fontWeight = FontWeight.Bold)) { append("[❄️冻结]") }
                                append(" | [进程: $pCount] | [$appName] 已冻结")
                            }
                        }
                    })
                }
            }

            LogEventType.DOZE_RESOURCE_REPORT -> {
                 withStyle(style = SpanStyle(color = Color.Magenta, fontWeight = FontWeight.Bold)) { append("[📊报告]") }
                 append(" | ${payload["title"]}")
                 subItems = mutableListOf()
                 val entries = payload["entries"] as? List<Map<String, Any>> ?: emptyList()
                 entries.forEach { entry ->
                     subItems?.add(buildAnnotatedString {
                        append("| ")
                        val appName = entry["app_name"] as? String ?: "N/A"
                        val time = (entry["active_time_sec"] as? Number)?.toFloat() ?: 0f
                        append("| [活跃: %.3f秒] | [$appName]".format(time))
                     })
                 }
            }

            LogEventType.APP_FROZEN, LogEventType.APP_STOP -> {
                val isFrozen = log.eventType == LogEventType.APP_FROZEN
                val icon = if(isFrozen) "[❄️冻结]" else "[⏹️关闭]"
                val color = if(isFrozen) Color(0xFF1A73E8) else Color.Gray
                withStyle(style = SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(icon) }
                
                val pCount = payload["pid_count"] as? Number
                if(pCount != null) append(" | [进程: ${pCount.toInt()}]")
                
                val session = payload["session_duration_s"] as? Number
                val cumulative = payload["cumulative_duration_s"] as? Number
                
                if(session != null) append(" | [运行时长: ${formatDuration(session.toLong())}]")
                
                append(" | [${payload["app_name"]}] 已${if(isFrozen) "冻结" else "关闭"}")
                
                if(cumulative != null) {
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.outline)) {
                        append(" (累计: ${formatDuration(cumulative.toLong())})")
                    }
                }
            }

            LogEventType.APP_START -> {
                withStyle(style = SpanStyle(color = Color.Green, fontWeight = FontWeight.Bold)) { append("[🚀启动]") }
                append(" | ")
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("[${payload["app_name"]}]") }
                append(" 新进程已创建")
            }

            LogEventType.APP_UNFROZEN -> {
                withStyle(style = SpanStyle(color = Color(0xFF34A853), fontWeight = FontWeight.Bold)) { append("[☀️解冻]") }
                append(" | ")
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("[${payload["app_name"]}]") }
                append(" 已解冻 (原因: ${payload["reason"]})")
            }

            LogEventType.APP_FOREGROUND -> {
                withStyle(style = SpanStyle(color = Color(0xFF34A853), fontWeight = FontWeight.Bold)) { append("[▶️打开]") }
                append(" | [${payload["app_name"]}] 已打开")
            }

            LogEventType.SCHEDULED_TASK_EXEC -> {
                withStyle(style = SpanStyle(color = Color.Cyan, fontWeight = FontWeight.Bold)) { append("[⏰定时]") }
                append(" | [操作: ${payload["operation"]}] | [${payload["targets"]}] 任务执行")
            }

            else -> {
                withStyle(style = SpanStyle(color = Color.DarkGray, fontWeight = FontWeight.Bold)) { append("[ℹ️信息]") }
                val message = (payload["message"] as? String) ?: payload.toString()
                append(" | $message")
            }
        }
    }
    return Pair(mainContent, subItems)
}

fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds < 0) return "N/A"
    if (totalSeconds == 0L) return "0秒"
    val hours = TimeUnit.SECONDS.toHours(totalSeconds)
    val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
    val seconds = totalSeconds % 60
    
    val builder = StringBuilder()
    if (hours > 0) builder.append("${hours}时")
    if (minutes > 0) builder.append("${minutes}分")
    if (seconds > 0 || builder.isEmpty()) builder.append("${seconds}秒")
    
    return builder.toString()
}

@Composable
fun ResourceStatistics() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("资源统计 (待实现)")
    }
}