// app/src/main/java/com/crfzit/crfzit/ui/logs/LogsScreen.kt
package com.crfzit.crfzit.ui.logs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

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
                items(
                    items = uiState.logs,
                    key = { log ->
                        "${log.originalLog.timestamp}-${log.originalLog.message}"
                    }
                ) { log ->
                    // [核心修改] 条件渲染
                    if (log.originalLog.category == "报告" && log.originalLog.details != null) {
                        ReportLogItem(log)
                    } else {
                        LogItem(log)
                    }
                }
            }
        }
    }
}

// [新增] Doze报告的数据模型
data class DozeProcessActivity(val process_name: String, val cpu_seconds: Double)
data class DozeAppActivity(val app_name: String, val package_name: String, val total_cpu_seconds: Double, val processes: List<DozeProcessActivity>)

// [新增] 用于显示Doze报告的Composable
@Composable
fun ReportLogItem(log: UiLogEntry) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val originalLog = log.originalLog
    val (icon, color) = getLogAppearance(originalLog.level)
    
    val dozeReportData: List<DozeAppActivity> = remember(originalLog.details) {
        try {
            val type = object : TypeToken<List<DozeAppActivity>>() {}.type
            Gson().fromJson(originalLog.details, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatter.format(Date(originalLog.timestamp)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$icon [${originalLog.category}]",
                    color = color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = originalLog.message,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Spacer(Modifier.height(8.dp))
            
            if (dozeReportData.isEmpty()) {
                Text("Doze期间无明显应用活动。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
            } else {
                dozeReportData.forEach { appActivity ->
                    Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
                        Text(
                            buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append("▶ ${appActivity.app_name}")
                                }
                                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                    append(" (${appActivity.package_name})")
                                }
                                append(" - 总计: ${"%.3f".format(appActivity.total_cpu_seconds)}s")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 16.sp
                        )

                        if (appActivity.processes.size > 1 || (appActivity.processes.isNotEmpty() && appActivity.processes[0].process_name != appActivity.package_name)) {
                            appActivity.processes.forEach { process ->
                                Text(
                                    text = "  - ${process.process_name}: ${"%.3f".format(process.cpu_seconds)}s",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.padding(start = 16.dp),
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun LogItem(log: UiLogEntry) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val originalLog = log.originalLog

    val (icon, color) = getLogAppearance(originalLog.level)
    val categoryString = originalLog.category

    val displayAppName = log.appName ?: originalLog.packageName

    val annotatedString = buildAnnotatedString {
        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
            // [修正] 使用 .timestamp 替换 .timestamp_ms
            append(formatter.format(Date(originalLog.timestamp)))
        }
        append(" ")

        withStyle(style = SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
            append("$icon[$categoryString]")
        }
        append(" ")

        if (!displayAppName.isNullOrEmpty()) {
            append("应用 ‘")
            withStyle(style = SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)) {
                append(displayAppName)
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