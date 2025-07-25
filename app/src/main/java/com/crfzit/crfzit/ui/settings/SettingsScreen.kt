// app/src/main/java/com/crfzit/crfzit/ui/settings/SettingsScreen.kt
package com.crfzit.crfzit.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsGroup(title = "核心策略") {
                    TimeoutSlider(
                        title = "智能模式后台超时",
                        value = uiState.standardTimeoutSec.toFloat(),
                        onValueChange = { viewModel.setStandardTimeout(it.roundToInt()) },
                        valueRange = 30f..300f,
                        unit = "秒"
                    )
                }
            }

            // [核心新增] 定时解冻设置组
            item {
                SettingsGroup(title = "定时解冻 (心跳)") {
                    SwitchSetting(
                        title = "启用定时解冻",
                        subtitle = "定期唤醒应用以同步消息，平衡续航与通知",
                        checked = uiState.isTimedUnfreezeEnabled,
                        onCheckedChange = viewModel::setTimedUnfreezeEnabled
                    )
                    
                    // 只有在启用时才显示滑块
                    if (uiState.isTimedUnfreezeEnabled) {
                        Spacer(Modifier.height(16.dp))
                        TimeoutSlider(
                            title = "解冻间隔",
                            value = uiState.timedUnfreezeIntervalSec.toFloat(),
                            onValueChange = { viewModel.setTimedUnfreezeInterval(it.roundToInt()) },
                            valueRange = 300f..7200f, // 5分钟到2小时
                            unit = "分钟",
                            displayValueTransform = { (it / 60).roundToInt() } // 将秒转换为分钟显示
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            content()
        }
    }
}

@Composable
fun SwitchSetting(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun TimeoutSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String,
    displayValueTransform: (Float) -> Int = { it.roundToInt() }
) {
    var sliderPosition by remember(value) { mutableFloatStateOf(value) }
    
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, modifier = Modifier.weight(1f))
            Text(
                "${displayValueTransform(sliderPosition)} $unit",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline
            )
        }
        Slider(
            value = sliderPosition,
            onValueChange = { sliderPosition = it },
            valueRange = valueRange,
            steps = ((valueRange.endInclusive - valueRange.start) / (if(unit == "分钟") 300f else 10f)).roundToInt() - 1,
            onValueChangeFinished = {
                onValueChange(sliderPosition)
            }
        )
    }
}