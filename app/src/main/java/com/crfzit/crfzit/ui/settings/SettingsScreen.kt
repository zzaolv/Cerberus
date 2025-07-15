package com.crfzit.crfzit.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                SettingsGroup(title = "核心引擎") {
                    SettingsDropdown("Freezer类型", listOf("自动选择", "cgroup", "SIGSTOP"))
                    SettingsSlider("后台超时 (智能)", 60f, "秒")
                    SettingsSwitch("定时解冻", false)
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
                    InfoItem("守护进程状态", "✅ 运行中 (PID: 12345)")
                    InfoItem("探针状态", "✅ 已在 system_server 激活")
                    ButtonItem("健康检查") {}
                    ButtonItem("重启守护进程") {}
                }
            }
            item {
                SettingsGroup(title = "关于") {
                    InfoItem("版本", "1.1 (UI-dev)")
                    InfoItem("作者", "Your Name")
                }
            }
        }
    }
}

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

@Composable
fun SettingsSlider(title: String, initialValue: Float, unit: String) {
    var sliderPosition by remember { mutableFloatStateOf(initialValue) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("$title: ${sliderPosition.toInt()} $unit")
        Slider(
            value = sliderPosition,
            onValueChange = { sliderPosition = it },
            valueRange = 10f..300f
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDropdown(title: String, options: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    var selectedOptionText by remember { mutableStateOf(options[0]) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            readOnly = true,
            value = selectedOptionText,
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
                        selectedOptionText = selectionOption
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
fun InfoItem(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text(value)
    }
}

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