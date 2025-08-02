// app/src/main/java/com/crfzit/crfzit/ui/logs/LogsScreen.kt
package com.crfzit.crfzit.ui.logs

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
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
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
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
                        "${log.originalLog.timestamp}-${log.originalLog.message}-${log.originalLog.packageName}"
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
                    lastVisibleItem != null && lastVisibleItem.index >= layoutInfo.totalItemsCount - 5
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

// [核心修复] 重命名数据类以匹配C++侧的JSON结构和逻辑命名
data class ProcessActivity(
    @SerializedName("process_name")
    val processName: String,
    @SerializedName("cpu_seconds")
    val cpuSeconds: Double
)
data class AppActivitySummary(
    @SerializedName("app_name")
    val appName: String,
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("total_cpu_seconds")
    val totalCpuSeconds: Double,
    val processes: List<ProcessActivity>
)


@Composable
fun LogItem(log: UiLogEntry) {
    val originalLog = log.originalLog
    val isReport = originalLog.category == "报告" && originalLog.details != null && !originalLog.details.isJsonNull

    val cardColors = if (isReport) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    } else {
        CardDefaults.cardColors(containerColor = Color.Transparent)
    }

    val cardElevation = if (isReport) CardDefaults.cardElevation() else CardDefaults.cardElevation(defaultElevation = 0.dp)


    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = cardColors,
        elevation = cardElevation
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if(isReport) 12.dp else 0.dp,
                vertical = if(isReport) 12.dp else 2.dp
            )
        ) {
            val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            val (icon, color) = getLogAppearance(originalLog.level)
            val displayAppName = log.appName ?: originalLog.packageName
            val annotatedString = buildAnnotatedString {
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append(formatter.format(Date(originalLog.timestamp)))
                }
                append(" ")
                withStyle(style = SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                    append("$icon[${originalLog.category}]")
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
                )
            )

            AnimatedVisibility(visible = isReport) {
                ReportDetails(details = originalLog.details)
            }
        }
    }
}

@Composable
private fun ReportDetails(details: JsonElement?) {
    // [核心修复] 使用正确的数据类 AppActivitySummary 进行JSON解析
    val reportData: List<AppActivitySummary> = remember(details) {
        try {
            if (details != null && !details.isJsonNull) {
                val type = object : TypeToken<List<AppActivitySummary>>() {}.type
                Gson().fromJson(details, type) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("ReportDetails", "Failed to parse Doze report details.", e)
            emptyList()
        }
    }

    Spacer(Modifier.height(8.dp))

    if (reportData.isEmpty()) {
        Text("Doze期间无明显应用活动。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
    } else {
        reportData.take(5).forEach { appActivity ->
            Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("▶ ${appActivity.appName}")
                        }
                        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append(" (${appActivity.packageName})")
                        }
                        append(" - 总计: ${"%.3f".format(appActivity.totalCpuSeconds)}s")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp
                )

                appActivity.processes.sortedByDescending { it.cpuSeconds }.take(3).forEach { process ->
                    Text(
                        text = "  - ${process.processName}: ${"%.3f".format(process.cpuSeconds)}s",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(start = 16.dp),
                        lineHeight = 16.sp
                    )
                }
            }
        }
        if (reportData.size > 5) {
            Text(
                text = "...等 ${reportData.size - 5} 个其他应用。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp)
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