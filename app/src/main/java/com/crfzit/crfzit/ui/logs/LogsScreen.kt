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
            appInfoRepository.loadAllInstalledApps(forceRefresh = true) // Always get fresh app list
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
            Text(content.first, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 18.sp)
            content.second?.forEach { subItem ->
                Text(subItem, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp), lineHeight = 18.sp)
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

    @Suppress("UNCHECKED_CAST")
    fun <T> Any?.safeAsListOfMaps(): List<Map<String, T>>? {
        if (this is List<*>) {
            return this.filterIsInstance<Map<String, T>>()
        }
        return null
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
                append(" | [状态: ${payload["status"]}] | ${payload["debug_info"]}")
            }
            LogEventType.BATCH_OPERATION_START -> {
                withStyle(style = SpanStyle(color = Color(0xFF1A73E8), fontWeight = FontWeight.Bold)) { append("[❄️批量处理]") }
                append(" | ${payload["title"]}")
                
                subItems = mutableListOf()
                val actions = payload["actions"].safeAsListOfMaps<Any>() ?: emptyList()
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
                                val pCount = (action["pid_count"] as? Double)?.toInt() ?: 1
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
                 val entries = payload["entries"].safeAsListOfMaps<Any>() ?: emptyList()
                 entries.forEach { entry ->
                     subItems?.add(buildAnnotatedString {
                        append("| ")
                        val appName = entry["app_name"] as? String ?: "N/A"
                        val time = (entry["active_time_sec"] as? Double)?.toFloat() ?: 0f
                        append("| [活跃: %.3f秒] | [$appName]".format(time))
                     })
                 }
            }
            LogEventType.APP_FROZEN, LogEventType.APP_STOP -> {
                val icon = if(log.eventType == LogEventType.APP_FROZEN) "[❄️冻结]" else "[⏹️关闭]"
                val color = if(log.eventType == LogEventType.APP_FROZEN) Color(0xFF1A73E8) else Color.Gray
                withStyle(style = SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(icon) }
                
                val pCount = payload["pid_count"] as? Number
                if(pCount != null) append(" | [进程: ${pCount.toInt()}]")
                
                val session = payload["session_duration_s"] as? Number
                val cumulative = payload["cumulative_duration_s"] as? Number
                
                if(session != null) append(" | [运行时长: ${formatDuration(session.toLong())}]")
                
                append(" | [${payload["app_name"]}] 已${if(log.eventType == LogEventType.APP_FROZEN) "冻结" else "关闭"}")
                
                if(cumulative != null) {
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.outline)) {
                        append(" (累计: ${formatDuration(cumulative.toLong())})")
                    }
                }
            }
             LogEventType.APP_FOREGROUND -> {
                withStyle(style = SpanStyle(color = Color(0xFF34A853), fontWeight = FontWeight.Bold)) { append("[▶️打开]") }
                append(" | [${payload["app_name"]}] 已打开")
            }
            LogEventType.APP_UNFROZEN -> {
                 withStyle(style = SpanStyle(color = Color(0xFF34A853), fontWeight = FontWeight.Bold)) { append("[☀️解冻]") }
                 append(" | [进程: ${(payload["pid_count"] as? Double)?.toInt() ?: 1}] | [${payload["app_name"]}] 已解冻")
            }
            else -> {
                withStyle(style = SpanStyle(color = Color.Gray)) { append("[ℹ️信息]") }
                append(" | ${log.payload}")
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
    return when {
        hours > 0 -> String.format("%d时%d分%d秒", hours, minutes, seconds)
        minutes > 0 -> String.format("%d分%d秒", minutes, seconds)
        else -> String.format("%d秒", seconds)
    }
}

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
                Text("暂无统计数据")
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
                painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.appInfo.icon)
                        .crossfade(true)
                        .build()
                ),
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
    val kb = bytes / 1024.0
    if (kb < 1024) return "${DecimalFormat("#.##").format(kb)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${DecimalFormat("#.##").format(mb)} MB"
    val gb = mb / 1024.0
    return "${DecimalFormat("#.##").format(gb)} GB"
}