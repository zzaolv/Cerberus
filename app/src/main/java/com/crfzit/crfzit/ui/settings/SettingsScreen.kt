// app/src/main/java/com/crfzit/crfzit/ui/settings/SettingsScreen.kt
package com.crfzit.crfzit.ui.settings

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * ViewModelFactory for providing Application context to SettingsViewModel.
 */
class SettingsViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/**
 * The main entry point for the Settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(LocalContext.current.applicationContext as Application))
) {
    val settingsState by viewModel.settingsState.collectAsState()
    val healthState by viewModel.healthCheckState.collectAsState()

    // Perform a health check when the screen is first composed.
    LaunchedEffect(Unit) {
        viewModel.performHealthCheck()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                SettingsGroup(title = "核心引擎") {
                    SettingsDropdown(
                        title = "Freezer类型",
                        options = FreezerType.entries.map { it.displayName },
                        selectedOption = settingsState.freezerType.displayName,
                        onOptionSelected = { selectedName ->
                            val type = FreezerType.entries.find { it.displayName == selectedName } ?: FreezerType.AUTO
                            viewModel.onFreezerTypeChanged(type)
                        }
                    )
                    SettingsSlider(
                        title = "定时解冻周期",
                        value = settingsState.unfreezeIntervalMinutes.toFloat(),
                        valueRange = 0f..180f, // 0 to 3 hours
                        onValueChange = { viewModel.onUnfreezeIntervalChanged(it.toInt()) },
                        unit = "分钟 (0为禁用)"
                    )
                }
            }
            item {
                SettingsGroup(title = "全局豁免") {
                    SettingsSwitch("智能推送感知", true)
                    SettingsSwitch("高网络活动感知", true)
                }
            }
            item {
                SettingsGroup(title = "模块信息与操作") {
                    if (healthState.isLoading) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val daemonStatusText = if (healthState.daemonPid > 0) "✅ 运行中 (PID: ${healthState.daemonPid})" else "❌ 未运行"
                        val probeStatusText = if (healthState.isProbeConnected) "✅ 已在 system_server 激活" else "❌ 连接中断"
                        InfoItem("守护进程状态", daemonStatusText)
                        InfoItem("探针状态", probeStatusText)
                    }
                    ButtonItem("健康检查", onClick = { viewModel.performHealthCheck() })
                    ButtonItem("重启守护进程", onClick = { viewModel.restartDaemon() })
                    ButtonItem("清空资源统计数据", onClick = { viewModel.clearStats() })
                }
            }
            item {
                SettingsGroup(title = "关于") {
                    InfoItem("版本", "1.2.0")
                    InfoItem("作者", "Cerberus Dev Team")
                }
            }
        }
    }
}

/**
 * A container for a group of related settings.
 */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * A setting item with a title and a toggle switch.
 */
@Composable
fun SettingsSwitch(title: String, initialValue: Boolean) {
    var checked by remember { mutableStateOf(initialValue) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { checked = !checked }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { checked = it })
    }
}

/**
 * A setting item with a title and a slider for selecting a numerical value.
 */
@Composable
fun SettingsSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    unit: String
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("$title: ${value.toInt()} $unit")
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = (valueRange.endInclusive - valueRange.start - 1).toInt().coerceAtLeast(0)
        )
    }
}

/**
 * A setting item that presents a dropdown menu for selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDropdown(
    title: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable),
            readOnly = true,
            value = selectedOption,
            onValueChange = {},
            label = { Text(title) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(selectionOption) },
                    onClick = {
                        onOptionSelected(selectionOption)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/**
 * A simple display item for showing key-value information.
 */
@Composable
fun InfoItem(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text(value)
    }
}

/**
 * A full-width button styled for use within a settings screen.
 */
@Composable
fun ButtonItem(title: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(title)
    }
}