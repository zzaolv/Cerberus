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
import kotlinx.coroutines.launch
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
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.isLoading) {
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
                        "${log.originalLog.timestamp}-${log.originalLog.packageName}-${log.originalLog.userId}-${log.originalLog.message}" 
                    }
                ) { log ->
                    LogItem(log)
                }
            }
            
            LaunchedEffect(uiState.logs.size) {
                 coroutineScope.launch {
                     if (uiState.logs.size > 1 && (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= uiState.logs.size - 2) {
                         listState.animateScrollToItem(uiState.logs.size - 1)
                     }
                 }
            }
        }
    }
}


@Composable
fun LogItem(log: UiLogEntry) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val originalLog = log.originalLog
    
    // [日志修复] 修复图标丢失的bug
    val (icon, color) = getLogAppearance(originalLog.level)
    val categoryString = originalLog.category
    
    // [日志修复] 定义最终显示的应用名，优先使用ViewModel解析出的名字，否则回退到包名
    val displayAppName = log.appName ?: originalLog.packageName
    
    val annotatedString = buildAnnotatedString {
        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
            append(formatter.format(Date(originalLog.timestamp)))
        }
        append(" ")
        
        // [日志修复] 正确拼接图标和分类
        withStyle(style = SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
            append("$icon[$categoryString]")
        }
        append(" ")

        // [日志修复] 如果日志有关联的应用，则构建 "应用 '名字'" 部分
        if (!displayAppName.isNullOrEmpty()) {
            append("应用 ‘")
            withStyle(style = SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(displayAppName)
            }
            append("’ ")
        }
        
        // 拼接后端传来的纯事件描述
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