// app/src/main/java/com/crfzit/crfzit/ui/stats/StatisticsScreen.kt
package com.crfzit.crfzit.ui.stats

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crfzit.crfzit.data.model.MetricsRecord
import kotlin.math.abs

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
                            if (it.memTotalKb > 0) (it.memTotalKb - it.memAvailableKb) * 100f / it.memTotalKb else 0f
                        },
                        range = 0f..100f,
                        labelFormat = "%.1f%%"
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
                        labelFormat = "%.1f°C"
                    )
                }
            }
        }
    }
}

@Composable
fun ChartCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
fun ChartLegend(coreCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until coreCount) {
            val color = ChartColors.getOrElse(i) { Color.Gray }
            Box(modifier = Modifier.size(10.dp).background(color, shape = RoundedCornerShape(2.dp)))
            Text(text = "C${i}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp, end = 12.dp))
        }
    }
}

@Composable
private fun rememberTextPaint(color: Color = Color.Black, spSize: Float = 12f, align: Paint.Align = Paint.Align.CENTER): Paint {
    val density = LocalDensity.current
    val textColor = color.toArgb()
    return remember(density, color, spSize, align) {
        Paint().apply {
            this.color = textColor; this.textAlign = align; this.textSize = with(density) { spSize.sp.toPx() }
        }
    }
}

fun DrawScope.drawChartScaffold(yRange: ClosedFloatingPointRange<Float>, yAxisPaint: Paint, yAxisLabelCount: Int = 5) {
    val gridPath = Path()
    val step = size.height / (yAxisLabelCount - 1)
    (0 until yAxisLabelCount).forEach { i ->
        val y = i * step
        gridPath.moveTo(0f, y); gridPath.lineTo(size.width, y)
        val labelValue = yRange.endInclusive - i * (yRange.endInclusive - yRange.start) / (yAxisLabelCount - 1)
        drawIntoCanvas { it.nativeCanvas.drawText("%.0f".format(labelValue), -12f, y + yAxisPaint.textSize / 3, yAxisPaint) }
    }
    drawPath(gridPath, Color.Gray.copy(alpha = 0.3f), style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
}

private data class LabelInfo(val index: Int, val x: Float, val y: Float, val text: String, val priority: Int)

@Composable
fun LineChart(
    modifier: Modifier = Modifier,
    records: List<MetricsRecord>,
    color: Color,
    valueExtractor: (MetricsRecord) -> Float,
    range: ClosedFloatingPointRange<Float>? = null,
    labelFormat: String = "%.1f"
) {
    // [核心修复] 1. 创建三种对齐方式的画笔
    val leftTextPaint = rememberTextPaint(color = MaterialTheme.colorScheme.onSurface, spSize = 10f, align = Paint.Align.LEFT)
    val centerTextPaint = rememberTextPaint(color = MaterialTheme.colorScheme.onSurface, spSize = 10f, align = Paint.Align.CENTER)
    val rightTextPaint = rememberTextPaint(color = MaterialTheme.colorScheme.onSurface, spSize = 10f, align = Paint.Align.RIGHT)
    val yAxisPaint = rememberTextPaint(color = MaterialTheme.colorScheme.onSurfaceVariant, spSize = 10f, align = Paint.Align.RIGHT)
    val labelCollisionThreshold: Dp = 40.dp

    Canvas(modifier = modifier.fillMaxWidth().height(150.dp).padding(start = 32.dp)) {
        val yValues = records.map(valueExtractor)
        val yMin = range?.start ?: (yValues.minOrNull() ?: 0f)
        val yMax = range?.endInclusive ?: (yValues.maxOrNull() ?: 100f)
        val yRangeValue = (yMax - yMin).coerceAtLeast(1f)

        drawChartScaffold(yRange = yMin..yMax, yAxisPaint = yAxisPaint)
        if (records.isEmpty()) return@Canvas

        val xStep = if (records.size > 1) size.width / (records.size - 1) else size.width / 2
        val path = Path()

        val labelsToConsider = mutableMapOf<Int, LabelInfo>()
        if (records.isNotEmpty()) {
            val latestIndex = records.indices.last
            val minIndex = yValues.withIndex().minByOrNull { it.value }?.index ?: -1
            val maxIndex = yValues.withIndex().maxByOrNull { it.value }?.index ?: -1

            val latestY = size.height - ((yValues[latestIndex] - yMin) / yRangeValue * size.height)
            labelsToConsider[latestIndex] = LabelInfo(latestIndex, if (records.size == 1) xStep else latestIndex * xStep, latestY, labelFormat.format(yValues[latestIndex]), 3)

            if (maxIndex != -1) {
                val maxY = size.height - ((yValues[maxIndex] - yMin) / yRangeValue * size.height)
                labelsToConsider[maxIndex] = LabelInfo(maxIndex, if (records.size == 1) xStep else maxIndex * xStep, maxY, labelFormat.format(yValues[maxIndex]), 2)
            }
            if (minIndex != -1) {
                val minY = size.height - ((yValues[minIndex] - yMin) / yRangeValue * size.height)
                labelsToConsider[minIndex] = LabelInfo(minIndex, if (records.size == 1) xStep else minIndex * xStep, minY, labelFormat.format(yValues[minIndex]), 1)
            }
        }

        val finalLabels = mutableListOf<LabelInfo>()
        val sortedLabels = labelsToConsider.values.sortedByDescending { it.priority }
        for (label in sortedLabels) {
            var hasCollision = false
            for (existingLabel in finalLabels) {
                if (abs(label.x - existingLabel.x) < labelCollisionThreshold.toPx()) { hasCollision = true; break }
            }
            if (!hasCollision) { finalLabels.add(label) }
        }

        records.forEachIndexed { index, _ ->
            val x = if (records.size == 1) xStep else index * xStep
            val y = size.height - ((yValues[index] - yMin) / yRangeValue * size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            if (records.size == 1) drawCircle(color, radius = 8f, center = Offset(x, y))
        }
        if (records.size > 1) drawPath(path = path, color = color, style = Stroke(width = 4f))

        finalLabels.forEach { label ->
            // [核心修复] 2. 边界感知和动态对齐
            val paint = when {
                label.x < 20.dp.toPx() -> leftTextPaint // 靠近左边界，左对齐
                label.x > size.width - 20.dp.toPx() -> rightTextPaint // 靠近右边界，右对齐
                else -> centerTextPaint // 其他情况居中
            }
            // [核心修复] 3. 增强垂直避让
            val yOffset = when {
                label.y < 20f -> 30f // 离顶部太近，向下翻转
                label.y > size.height - 20f -> -30f // 离底部太近，向上翻转
                else -> -15f // 默认在上方
            }
            drawIntoCanvas {
                it.nativeCanvas.drawText(label.text, label.x, label.y + yOffset, paint)
            }
        }
    }
}

@Composable
fun MultiLineChart(
    modifier: Modifier = Modifier,
    records: List<MetricsRecord>,
    valueExtractor: (MetricsRecord) -> List<Float>,
    range: ClosedFloatingPointRange<Float>
) {
    val yAxisPaint = rememberTextPaint(color = MaterialTheme.colorScheme.onSurfaceVariant, spSize = 10f, align = Paint.Align.RIGHT)
    Canvas(modifier = modifier.fillMaxWidth().height(150.dp).padding(start = 32.dp)) {
        drawChartScaffold(yRange = range, yAxisPaint = yAxisPaint)
        if (records.isEmpty()) return@Canvas
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
                if (records.size == 1) drawCircle(color, radius = 8f, center = Offset(x, y))
            }
            if (records.size > 1) drawPath(path = path, color = color, style = Stroke(width = 4f))
        }
    }
}