// app/src/main/java/com/crfzit/crfzit/ui/settings/SettingsScreen.kt (重写)
package com.crfzit.crfzit.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            // Future settings groups will go here
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
fun TimeoutSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String
) {
    var sliderPosition by remember(value) { mutableFloatStateOf(value) }
    
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, modifier = Modifier.weight(1f))
            Text("${sliderPosition.roundToInt()} $unit", style = MaterialTheme.typography.bodyLarge)
        }
        Slider(
            value = sliderPosition,
            onValueChange = { sliderPosition = it },
            valueRange = valueRange,
            onValueChangeFinished = {
                onValueChange(sliderPosition)
            }
        )
    }
}