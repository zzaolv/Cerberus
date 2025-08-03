// app/src/main/java/com/crfzit/crfzit/ui/stats/StatisticsScreen.kt
package com.crfzit.crfzit.ui.stats

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crfzit.crfzit.data.model.MetricsRecord
import kotlin.math.roundToInt

// [核心新增] 定义一组图表颜色
private val ChartColors = listOf(
    Color(0xFFF44336), Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFFFFC107),
    Color(0xFF9C27B0), Color(0xFF00BCD4), Color(0xFFE91E63), Color(0xFFFF9800)
)

@Composable
fun StatisticsScreen(viewModel: StatisticsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.isLoading && uiState.records.isEmpty()) {
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
                val coreCount = uiState.records.firstOrNull()?.perCoreCpuUsagePercent?.size ?: 0
                ChartCard(title = "CPU 使用率 (%)") {
                    MultiLineChart(
                        records = uiState.records,
                        valueExtractor = { it.perCoreCpuUsagePercent },
                        range = 0f..100f
                    )
                    if (coreCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        ChartLegend(coreCount = coreCount)
                    }
                }
            }
            item {
                ChartCard(title = "内存使用率 (%)") {
                    LineChart(
                        records = uiState.records,
                        color = Color(0xFF34A853),
                        valueExtractor = {
                            if (it.memTotalKb > 0) {
                                (it.memTotalKb - it.memAvailableKb) * 100f / it.memTotalKb
                            } else 0f
                        },
                        range = 0f..100f,
                        showValueLabels = true // 启用数值标签
                    )
                }
            }
            item {
                ChartCard(title = "电池温度 (°C)") {
                    LineChart(
                        records = uiState.records,
                        color = Color(0xFFF4B400),
                        valueExtractor = { it.batteryTempCelsius },
                        range = 20f..50f,
                        showValueLabels = true // 启用数值标签
                    )
                }
            }
        }
    }
}

@Composable
fun ChartCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

// [核心新增] CPU图表下方的图例
@Composable
fun ChartLegend(coreCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until coreCount) {
            val color = ChartColors.getOrElse(i) { Color.Gray }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, shape = RoundedCornerShape(2.dp))
            )
            Text(
                text = "C${i}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp)
            )
        }
    }
}

// [核心新增] 绘制网格和标签的辅助函数
fun DrawScope.drawChartGrid(
    yAxisLabelCount: Int = 5,
    yRange: ClosedFloatingPointRange<Float>
) {
    val gridPath = Path()
    val step = size.height / (yAxisLabelCount - 1)
    (0 until yAxisLabelCount).forEach { i ->
        gridPath.moveTo(0f, i * step)
        gridPath.lineTo(size.width, i * step)
    }
    drawPath(
        gridPath,
        Color.Gray.copy(alpha = 0.3f),
        style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
    )
}

// [核心新增] 绘制文本标签的辅助函数
@Composable
private fun rememberTextPaint(color: Color = Color.Black, spSize: Float = 12f): Paint {
    val density = LocalDensity.current
    val textColor = color.toArgb()
    return remember(density, color, spSize) {
        Paint().apply {
            this.color = textColor
            this.textAlign = Paint.Align.CENTER
            this.textSize = with(density) { spSize.sp.toPx() }
        }
    }
}

// [核心修改] 为单线图表增加数值标签和空状态支持
@Composable
fun LineChart(
    modifier: Modifier = Modifier,
    records: List<MetricsRecord>,
    color: Color,
    valueExtractor: (MetricsRecord) -> Float,
    range: ClosedFloatingPointRange<Float>? = null,
    showValueLabels: Boolean = false
) {
    val textPaint = rememberTextPaint(color = MaterialTheme.colorScheme.onSurface, spSize = 10f)

    Canvas(modifier = modifier.fillMaxWidth().height(150.dp)) {
        val yValues = records.map(valueExtractor)
        val yMin = range?.start ?: (yValues.minOrNull() ?: 0f)
        val yMax = range?.endInclusive ?: (yValues.maxOrNull() ?: 100f)
        val yRange = (yMax - yMin).coerceAtLeast(1f)

        drawChartGrid(yRange = yMin..yMax)

        if (records.isEmpty()) {
            return@Canvas // 数据为空，只画网格
        }

        val xStep = if (records.size > 1) size.width / (records.size - 1) else size.width / 2

        val path = Path()
        records.forEachIndexed { index, record ->
            val x = if (records.size == 1) xStep else index * xStep
            val yValue = valueExtractor(record)
            val y = size.height - ((yValue - yMin) / yRange * size.height)
            
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)

            if (records.size == 1) { // 只有一个点时画一个圆
                drawCircle(color, radius = 8f, center = Offset(x, y))
            }
            if (showValueLabels) {
                drawIntoCanvas {
                    it.nativeCanvas.drawText("%.1f".format(yValue), x, y - 15, textPaint)
                }
            }
        }

        if (records.size > 1) {
            drawPath(path = path, color = color, style = Stroke(width = 4f))
        }
    }
}

// [核心新增] 绘制多核心CPU图表
@Composable
fun MultiLineChart(
    modifier: Modifier = Modifier,
    records: List<MetricsRecord>,
    valueExtractor: (MetricsRecord) -> List<Float>,
    range: ClosedFloatingPointRange<Float>
) {
    Canvas(modifier = modifier.fillMaxWidth().height(150.dp)) {
        drawChartGrid(yRange = range)

        if (records.isEmpty()) {
            return@Canvas
        }
        
        val coreCount = records.first().perCoreCpuUsagePercent.size
        if (coreCount == 0) return@Canvas

        val xStep = if (records.size > 1) size.width / (records.size - 1) else size.width / 2
        
        for (coreIndex in 0 until coreCount) {
            val path = Path()
            val color = ChartColors.getOrElse(coreIndex) { Color.Gray }
            records.forEachIndexed { recordIndex, record ->
                val x = if (records.size == 1) xStep else recordIndex * xStep
                val yValue = record.perCoreCpuUsagePercent.getOrElse(coreIndex) { 0f }
                val y = size.height - ((yValue - range.start) / (range.endInclusive - range.start) * size.height)

                if (recordIndex == 0) path.moveTo(x, y) else path.lineTo(x, y)

                if (records.size == 1) {
                    drawCircle(color, radius = 8f, center = Offset(x, y))
                }
            }
            if (records.size > 1) {
                drawPath(path = path, color = color, style = Stroke(width = 4f))
            }
        }
    }
}