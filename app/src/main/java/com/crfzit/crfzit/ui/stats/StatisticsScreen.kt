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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.draw.drawText
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
                    title = "内存使用 (MB)",
                    records = uiState.records,
                    color = Color(0xFF34A853),
                    valueExtractor = { it.memUsedKb / 1024f }
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
    val yMin = range?.start ?: (values.minOrNull() ?: 0f)
    val yMax = range?.endInclusive ?: (values.maxOrNull() ?: 0f)
    val yRange = if (yMax - yMin == 0f) 1f else yMax - yMin

    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier.fillMaxWidth().height(150.dp)) {
        val xStep = size.width / (records.size - 1)
        val path = Path()

        records.forEachIndexed { index, record ->
            val x = index * xStep
            val y = size.height - ((valueExtractor(record) - yMin) / yRange * size.height)
            if (index == 0) {
                path.moveTo(x, y.toFloat())
            } else {
                path.lineTo(x, y.toFloat())
            }
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 4f)
        )

        // Draw Y-axis labels
        val yLabelMax = textMeasurer.measure(yMax.roundToInt().toString(), TextStyle(fontSize = 12.sp))
        val yLabelMin = textMeasurer.measure(yMin.roundToInt().toString(), TextStyle(fontSize = 12.sp))
        
        drawText(yLabelMax, topLeft = Offset(5f, -5f))
        drawText(yLabelMin, topLeft = Offset(5f, size.height - yLabelMin.size.height))
        
        // Draw grid lines
        val gridPath = Path()
        gridPath.moveTo(0f, size.height / 2)
        gridPath.lineTo(size.width, size.height / 2)
        drawPath(gridPath, Color.Gray, style = Stroke(width=1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
    }
}