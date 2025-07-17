// app/src/main/java/com/crfzit/crfzit/ui/logs/LogsScreen.kt
package com.crfzit.crfzit.ui.logs

import android.app.Application
import android.util.Log
import androidx.compose.foundation.Image
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.crfzit.crfzit.CerberusApplication
import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.LogEntry
import com.crfzit.crfzit.data.model.LogEventType
import com.crfzit.crfzit.data.repository.AppInfoRepository
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit


// 【新增】ResourceStatsViewModel 用于资源统计页面
class ResourceStatsViewModel(application: Application) : AndroidViewModel(application) {
    private val appInfoRepository = AppInfoRepository.getInstance(application)
    private val appScope = (application as CerberusApplication).applicationScope
    private val udsClient = UdsClient.getInstance(appScope)
    private val gson = Gson()

    data class AppStatItem(
        val appInfo: AppInfo,
        val cpuSeconds: Long,
        val trafficBytes: Long,
        val wakeups: Long
    )

    private val _stats = MutableStateFlow<List<AppStatItem>>(emptyList())
    val stats: StateFlow<List<AppStatItem>> = _stats.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            appInfoRepository.loadAllInstalledApps(forceRefresh = true)
            observeStatsResponse()
            requestStats()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun observeStatsResponse() {
        viewModelScope.launch {
            udsClient.incomingMessages.filter { it.contains("resp.resource_stats") }.collect { jsonLine ->
                try {
                    val response = gson.fromJson(jsonLine, Map::class.java)
                    val payload = response["payload"] as? List<Map<String, Any>> ?: emptyList()

                    val localApps = appInfoRepository.getCachedApps()
                    val statItems = payload.mapNotNull { statMap ->
                        val pkgName = statMap["package_name"] as? String ?: return@mapNotNull null
                        localApps[pkgName]?.let { appInfo ->
                            AppStatItem(
                                appInfo = appInfo,
                                cpuSeconds = (statMap["cpu_seconds"] as? Double)?.toLong() ?: 0L,
                                trafficBytes = (statMap["traffic_bytes"] as? Double)?.toLong() ?: 0L,
                                wakeups = (statMap["wakeups"] as? Double)?.toLong() ?: 0L
                            )
                        }
                    }
                    _stats.value = statItems
                    _isLoading.value = false
                } catch (e: Exception) {
                    Log.e("ResourceStatsVM", "Error parsing stats response", e)
                    _isLoading.value = false
                }
            }
        }
    }

    fun requestStats() {
        _isLoading.value = true
        val request = mapOf("v" to 1, "type" to "query.get_resource_stats", "req_id" to UUID.randomUUID().toString())
        udsClient.sendMessage(gson.toJson(request))
    }
}


// 【新增】统一的 ViewModelFactory
class LogsViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(LogsViewModel::class.java) -> LogsViewModel(application) as T
            modelClass.isAssignableFrom(ResourceStatsViewModel::class.java) -> ResourceStatsViewModel(application) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    logsViewModel: LogsViewModel = viewModel(factory = LogsViewModelFactory(LocalContext.current.applicationContext as Application))
) {
    val uiState by logsViewModel.uiState.collectAsState()
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
                    onLoadMore = { logsViewModel.loadMoreLogs() }
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
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.logs, key = { it.timestamp.toString() + it.payload.toString() }) { log ->
                    LogItem(log)
                }
                if (state.isLoading && state.logs.isNotEmpty()) {
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

// 【核心重构】LogItem现在处理多行和缩进
@Composable
fun LogItem(log: LogEntry) {
    val content = buildLogContent(log = log)

    Column(Modifier.padding(vertical = 2.dp)) {
        Text(
            text = content.first,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        // 渲染缩进的子项
        content.second?.forEach { subItem ->
            Text(
                text = subItem,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(start = 8.dp) // 缩进
            )
        }
    }
}

// 【核心重构】日志内容构建函数，完全根据您的格式要求实现
@Composable
private fun buildLogContent(log: LogEntry): Pair<AnnotatedString, List<AnnotatedString>?> {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timestamp = formatter.format(Date(log.timestamp))
    var subItems: MutableList<AnnotatedString>? = null

    val payload = log.payload

    // 安全地从 payload 获取值
    val appName = payload["app_name"] as? String
    val pidCount = (payload["pid_count"] as? Double)?.toInt()
    val sessionDuration = (payload["session_duration_s"] as? Double)?.toLong()
    val cumulativeDuration = (payload["cumulative_duration_s"] as? Double)?.toLong()
    val message = payload["message"] as? String
    val reason = payload["reason"] as? String

    val mainContent = buildAnnotatedString {
        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.outline)) { append("[$timestamp]") }
        append(" | ")

        when (log.eventType) {
            LogEventType.APP_FOREGROUND -> {
                append("[▶️打开] | [${appName ?: "未知应用"}] 已打开")
            }
            LogEventType.APP_STOP -> {
                append("[⏹️关闭] | ")
                if (sessionDuration != null) append("[运行时长: ${formatDuration(sessionDuration)}] | ")
                append("[${appName ?: "未知应用"}] 已关闭")
                if (cumulativeDuration != null) {
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.outline)) {
                        append(" (累计: ${formatDuration(cumulativeDuration)})")
                    }
                }
            }
            LogEventType.APP_FROZEN -> {
                append("[❄️冻结] | ")
                if (pidCount != null) append("[进程: $pidCount] | ")
                append("[${appName ?: "未知应用"}] 已冻结")
                if (sessionDuration != null && cumulativeDuration != null) {
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.outline)) {
                        append(" (运行时长: ${formatDuration(sessionDuration)}, 累计: ${formatDuration(cumulativeDuration)})")
                    }
                }
            }
            LogEventType.APP_UNFROZEN -> {
                append("[☀️解冻] | ")
                if (pidCount != null) append("[进程: $pidCount] | ")
                append("[${appName ?: "未知应用"}] 已解冻")
                if (reason != null) {
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.outline)) {
                        append(" (原因: $reason)")
                    }
                }
            }
            LogEventType.GENERIC_INFO -> {
                append("[ℹ️信息] | [${appName ?: ""}] ${message ?: payload.toString()}")
            }
            LogEventType.POWER_UPDATE, LogEventType.POWER_WARNING -> {
                val icon = if(log.eventType == LogEventType.POWER_WARNING) "⚡️警告" else "🔋电量"
                val capacity = (payload["capacity"] as? Double)?.toInt()
                val consumption = (payload["consumption_percent"] as? Double)?.toInt()
                val duration = (payload["consumption_duration_min"] as? Double)?.toInt()
                val power = payload["power_watt"] as? Double
                val temp = payload["temperature"] as? Double
                val statusText = if(log.eventType == LogEventType.POWER_WARNING) "耗电较快" else "状态更新"

                append("[$icon] | ")
                if(capacity != null) append("[当前: $capacity%] | ")
                if(consumption != null && duration != null) append("[消耗: $consumption% / ${duration}分钟] | ")
                if(power != null) append("[功率: %.2fw] | ".format(power))
                if(temp != null) append("[温度: %.1f°C] ".format(temp))
                append(statusText)
            }
            LogEventType.SCHEDULED_TASK_EXEC -> {
                append("[⏰定时] | [操作: ${payload["operation"]}] | [${payload["targets"]}] 任务执行")
            }
            LogEventType.DOZE_STATE_CHANGE -> {
                append("[🌙Doze] | [状态: ${payload["status"]}] | ${payload["debug_info"]}")
            }
            LogEventType.BATCH_OPERATION_START -> {
                append("[❄️批量处理] | ${payload["title"]}")
                subItems = mutableListOf()
                @Suppress("UNCHECKED_CAST")
                val actions = payload["actions"] as? List<Map<String, Any>> ?: emptyList()
                actions.forEach { action ->
                    subItems?.add(buildAnnotatedString {
                        val actionAppName = action["app_name"] as? String
                        val actionRunTime = (action["runtime_s"] as? Double)?.toLong()
                        when(action["type"] as? String){
                            "network_block" -> {
                                append("| [❌断网] | [$actionAppName] 断网成功")
                                if (actionRunTime != null) append("并关闭 (运行: ${formatDuration(actionRunTime)})")
                            }
                            "freeze" -> {
                                val actionPidCount = (action["pid_count"] as? Double)?.toInt() ?: 1
                                append("| [❄️冻结] | [进程: $actionPidCount] | [$actionAppName] 已冻结")
                                if (actionRunTime != null) append(" (运行: ${formatDuration(actionRunTime)})")
                            }
                        }
                    })
                }
            }
            LogEventType.DOZE_RESOURCE_REPORT -> {
                append("[📊报告] | ${payload["title"]}")
                subItems = mutableListOf()
                @Suppress("UNCHECKED_CAST")
                val entries = payload["entries"] as? List<Map<String, Any>> ?: emptyList()
                entries.forEach { entry ->
                    subItems?.add(buildAnnotatedString {
                        val entryAppName = entry["app_name"] as? String
                        val activeTime = entry["active_time_sec"] as? Double
                        append("| | [活跃: %.3f秒] | [$entryAppName]".format(activeTime ?: 0.0))
                    })
                }
            }
            else -> {
                append("[⚙️系统] | ${payload.toString()}")
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
    return buildString {
        if (hours > 0) append("${hours}时")
        if (minutes > 0) append("${minutes}分")
        if (seconds > 0 || isEmpty()) append("${seconds}秒")
    }
}

// 【资源统计页面】
@Composable
fun ResourceStatistics(
    viewModel: ResourceStatsViewModel = viewModel(factory = LogsViewModelFactory(LocalContext.current.applicationContext as Application))
) {
    val stats by viewModel.stats.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var sortType by remember { mutableStateOf(SortType.CPU) }

    val sortedStats = remember(stats, sortType) {
        when (sortType) {
            SortType.CPU -> stats.sortedByDescending { it.cpuSeconds }
            SortType.TRAFFIC -> stats.sortedByDescending { it.trafficBytes }
            SortType.WAKEUPS -> stats.sortedByDescending { it.wakeups }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sortType.ordinal) {
            SortType.entries.forEach {
                Tab(
                    selected = sortType == it,
                    onClick = { sortType = it },
                    text = { Text(it.displayName) }
                )
            }
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (sortedStats.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("暂无统计数据")
                    Button(onClick = { viewModel.requestStats() }) {
                        Text("刷新")
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sortedStats, key = { it.appInfo.packageName }) { item ->
                    StatItemCard(item, sortType)
                }
            }
        }
    }
}

enum class SortType(val displayName: String) {
    CPU("CPU时间"), TRAFFIC("后台流量"), WAKEUPS("唤醒次数")
}

@Composable
fun StatItemCard(item: ResourceStatsViewModel.AppStatItem, sortType: SortType) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = rememberAsyncImagePainter(model = ImageRequest.Builder(LocalContext.current).data(item.appInfo.icon).crossfade(true).build()),
                contentDescription = item.appInfo.appName,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.appInfo.appName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.appInfo.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = when(sortType) {
                    SortType.CPU -> formatDuration(item.cpuSeconds)
                    SortType.TRAFFIC -> formatBytes(item.trafficBytes)
                    SortType.WAKEUPS -> "${item.wakeups} 次"
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val format = DecimalFormat("#.##")
    val kb = bytes / 1024.0
    if (kb < 1024) return "${format.format(kb)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${format.format(mb)} MB"
    val gb = mb / 1024.0
    return "${format.format(gb)} GB"
}