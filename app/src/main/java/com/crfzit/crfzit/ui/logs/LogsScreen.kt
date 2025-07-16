package com.crfzit.crfzit.ui.logs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crfzit.crfzit.data.model.LogEntry
import com.crfzit.crfzit.data.model.LogLevel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: LogsViewModel = viewModel()) {
    val logs by viewModel.logs.collectAsState()
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
                0 -> EventTimeline(logs)
                1 -> ResourceStatistics()
            }
        }
    }
}

@Composable
fun EventTimeline(logs: List<LogEntry>) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        reverseLayout = true
    ) {
        items(logs, key = { it.timestamp }) { log ->
            LogItem(log)
        }
    }
}

@Composable
fun LogItem(log: LogEntry) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val (icon, color) = getLogAppearance(log.level)

    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "${formatter.format(Date(log.timestamp))} $icon",
            color = color,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(log.message, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ResourceStatistics() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("资源统计图表 (待实现)")
    }
}

// <-- 关键修正：将此函数标记为 @Composable
@Composable
fun getLogAppearance(level: LogLevel): Pair<String, Color> {
    return when (level) {
        LogLevel.INFO -> "ℹ️" to MaterialTheme.colorScheme.outline
        LogLevel.SUCCESS -> "✅" to Color(0xFF34A853) // Green
        LogLevel.WARNING -> "⚠️" to Color(0xFFFBBC05) // Yellow
        LogLevel.ERROR -> "❌" to MaterialTheme.colorScheme.error
        // 现在可以安全地访问 MaterialTheme.colorScheme 了
        LogLevel.EVENT -> "⚡" to MaterialTheme.colorScheme.primary
    }
}