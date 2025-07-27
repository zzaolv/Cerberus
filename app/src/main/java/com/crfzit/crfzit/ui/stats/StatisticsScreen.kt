// app/src/main/java/com/crfzit/crfzit/ui/stats/StatisticsScreen.kt
package com.crfzit.crfzit.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crfzit.crfzit.data.model.MetricsRecord
import kotlin.math.roundToInt

@Composable
fun StatisticsScreen(viewModel: StatisticsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ChartCard(
                    title = "CPU 使用率 (%)",
                    records = uiState.records,
                    color = MaterialTheme.colorScheme.primary,
                    valueExtractor = { it.cpuUsagePercent },
                    range = 0f..100f
                )
            }
            item {
                ChartCard(
                    title = "内存使用率 (%)",
                    records = uiState.records,
                    color = Color(0xFF34A853),
                    valueExtractor = {
                        if (it.memTotalKb > 0) {
                            (it.memTotalKb - it.memAvailableKb) * 100f / it.memTotalKb
                        } else {
                            0f
                        }
                    },
                    range = 0f..100f
                )
            }
            item {
                ChartCard(
                    title = "电池温度 (°C)",
                    records = uiState.records,
                    color = Color(0xFFF4B400),
                    valueExtractor = { it.batteryTempCelsius },
                    range = 20f..50f
                )
            }
        }
    }
}

@Composable
fun ChartCard(
    title: String,
    records: List<MetricsRecord>,
    color: Color,
    valueExtractor: (MetricsRecord) -> Float,
    range: ClosedFloatingPointRange<Float>? = null
) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LineChart(
                records = records,
                color = color,
                valueExtractor = valueExtractor,
                range = range
            )
        }
    }
}

@Composable
fun LineChart(
    modifier: Modifier = Modifier,
    records: List<MetricsRecord>,
    color: Color,
    valueExtractor: (MetricsRecord) -> Float,
    range: ClosedFloatingPointRange<Float>? = null
) {
    if (records.size < 2) {
        Box(modifier = modifier.height(150.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("数据不足，无法绘制图表")
        }
        return
    }

    val values = records.map(valueExtractor)
    val yMinValue = range?.start ?: (values.minOrNull() ?: 0f)
    val yMaxValue = range?.endInclusive ?: (values.maxOrNull() ?: 0f)

    val yMin = if(yMaxValue - yMinValue < 1f) yMinValue - 5f else yMinValue
    val yMax = if(yMaxValue - yMinValue < 1f) yMaxValue + 5f else yMaxValue

    val yRange = (yMax - yMin).coerceAtLeast(1f) // 避免除以零

    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textStyle = TextStyle(fontSize = 12.sp, color = labelColor)

    Canvas(modifier = modifier.fillMaxWidth().height(150.dp)) {
        val xStep = if (records.size > 1) size.width / (records.size - 1) else 0f
        val path = Path()

        records.forEachIndexed { index, record ->
            val x = index * xStep
            val yValue = valueExtractor(record)
            val clampedYValue = yValue.coerceIn(yMin, yMax)
            val y = size.height - ((clampedYValue - yMin) / yRange * size.height)

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 4f)
        )

        // 简化版本：暂时移除文本标签，只保留图表
        // 如果需要文本标签，可以稍后添加

        val gridPath = Path()
        val midY = size.height / 2f
        gridPath.moveTo(0f, midY)
        gridPath.lineTo(size.width, midY)
        drawPath(
            gridPath,
            color.copy(alpha = 0.3f),
            style = Stroke(
                width=1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            )
        )
    }
}