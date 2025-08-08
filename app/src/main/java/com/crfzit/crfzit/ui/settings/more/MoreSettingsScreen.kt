// app/src/main/java/com/crfzit/crfzit/ui/settings/more/MoreSettingsScreen.kt
package com.crfzit.crfzit.ui.settings.more

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.crfzit.crfzit.data.model.Policy
import com.crfzit.crfzit.ui.icons.AppIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSettingsScreen(
    viewModel: MoreSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showBulkDialog by remember { mutableStateOf<Policy?>(null) }
    var showSigmoidDialog by remember { mutableStateOf<OomRule?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("更多设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.saveAndReloadRules() }) {
                        Icon(AppIcons.Save, contentDescription = "保存并重载")
                    }
                    IconButton(onClick = { viewModel.addNewRule() }) {
                        Icon(Icons.Default.Add, contentDescription = "添加规则")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("OOM 守护策略", style = MaterialTheme.typography.titleLarge)
                Text(
                    "规则按优先级从上到下匹配。调整后点击右上角保存图标以生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (uiState.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (uiState.adjRulesError != null) {
                item {
                    Text(uiState.adjRulesError!!, color = MaterialTheme.colorScheme.error)
                }
            }

            items(uiState.oomRules, key = { it.id }) { rule ->
                OomRuleCard(
                    rule = rule,
                    onRuleChange = viewModel::updateRule,
                    onDelete = { viewModel.deleteRule(rule.id) },
                    onEditSigmoid = { showSigmoidDialog = it }
                )
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                BulkOperationsCard(
                    isLoading = uiState.isLoading,
                    appCount = uiState.dataAppPackages.size,
                    onApplyPolicy = { policy -> showBulkDialog = policy }
                )
            }
        }
    }

    // [核心修复] 实现了确认对话框的逻辑
    showBulkDialog?.let { policy ->
        AlertDialog(
            onDismissRequest = { showBulkDialog = null },
            title = { Text("确认批量操作") },
            text = {
                Text("您确定要将 ${uiState.dataAppPackages.size} 个第三方应用的策略批量设置为 '${policy.displayName}' 吗？此操作会覆盖这些应用现有的策略配置。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.applyBulkPolicy(policy)
                        showBulkDialog = null
                    }
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBulkDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showSigmoidDialog != null) {
        val rule = showSigmoidDialog!!
        SigmoidParamsDialog(
            params = rule.params ?: SigmoidParams(),
            onDismiss = { showSigmoidDialog = null },
            onSave = { newParams ->
                viewModel.updateRule(rule.copy(params = newParams))
                showSigmoidDialog = null
            }
        )
    }
}

@Composable
fun OomRuleCard(
    rule: OomRule,
    onRuleChange: (OomRule) -> Unit,
    onDelete: () -> Unit,
    onEditSigmoid: (OomRule) -> Unit
) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("规则", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除规则", tint = MaterialTheme.colorScheme.error)
                }
            }

            Text("源范围 (Original Adj)", style = MaterialTheme.typography.labelLarge)
            RangeSlider(
                value = rule.sourceRange[0].toFloat()..rule.sourceRange[1].toFloat(),
                onValueChange = { newRange ->
                    onRuleChange(rule.copy(sourceRange = listOf(newRange.start.toInt(), newRange.endInclusive.toInt())))
                },
                valueRange = -1000f..1001f,
                steps = 2000
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(rule.sourceRange[0].toString(), style = MaterialTheme.typography.bodySmall)
                Text(rule.sourceRange[1].toString(), style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            Text("映射类型", style = MaterialTheme.typography.labelLarge)
            RuleTypeSelector(
                selectedType = rule.type,
                onTypeSelected = { newType -> onRuleChange(rule.copy(type = newType)) }
            )

            Spacer(Modifier.height(16.dp))
            AnimatedContent(targetState = rule.type, label = "RuleTypeTransition") { type ->
                when (type) {
                    "linear" -> {
                        Column {
                            Text("目标范围 (Target Adj)", style = MaterialTheme.typography.labelLarge)
                            RangeSlider(
                                value = (rule.targetRange?.getOrNull(0)?.toFloat() ?: 0f)..(rule.targetRange?.getOrNull(1)?.toFloat() ?: 0f),
                                onValueChange = { newRange ->
                                    onRuleChange(rule.copy(targetRange = listOf(newRange.start.toInt(), newRange.endInclusive.toInt())))
                                },
                                valueRange = -1000f..1001f,
                                steps = 2000
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(rule.targetRange?.getOrNull(0)?.toString() ?: "0", style = MaterialTheme.typography.bodySmall)
                                Text(rule.targetRange?.getOrNull(1)?.toString() ?: "0", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    "sigmoid" -> {
                        val params = rule.params ?: SigmoidParams()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .clickable { onEditSigmoid(rule) }
                                .padding(12.dp)
                        ) {
                            Text("Sigmoid 参数 (点击编辑)", style = MaterialTheme.typography.labelLarge)
                            Text("目标: ${params.targetMin} ~ ${params.targetMax}", style = MaterialTheme.typography.bodyMedium)
                            Text("拐点: ${params.midpoint}, 陡峭度: ${params.steepness}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleTypeSelector(selectedType: String, onTypeSelected: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = selectedType == "linear",
            onClick = { onTypeSelected("linear") },
            shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
        ) { Text("线性映射") }
        SegmentedButton(
            selected = selectedType == "sigmoid",
            onClick = { onTypeSelected("sigmoid") },
            shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
        ) { Text("Sigmoid 曲线") }
    }
}

@Composable
fun SigmoidParamsDialog(
    params: SigmoidParams,
    onDismiss: () -> Unit,
    onSave: (SigmoidParams) -> Unit
) {
    var targetMin by remember { mutableStateOf(params.targetMin.toString()) }
    var targetMax by remember { mutableStateOf(params.targetMax.toString()) }
    var midpoint by remember { mutableStateOf(params.midpoint.toString()) }
    var steepness by remember { mutableStateOf(params.steepness.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 Sigmoid 参数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = targetMin, onValueChange = { targetMin = it }, label = { Text("目标最小值") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = targetMax, onValueChange = { targetMax = it }, label = { Text("目标最大值") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = midpoint, onValueChange = { midpoint = it }, label = { Text("拐点 (Midpoint)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = steepness, onValueChange = { steepness = it }, label = { Text("陡峭度 (Steepness)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(SigmoidParams(
                    targetMin = targetMin.toDoubleOrNull() ?: 0.0,
                    targetMax = targetMax.toDoubleOrNull() ?: 0.0,
                    midpoint = midpoint.toDoubleOrNull() ?: 0.0,
                    steepness = steepness.toDoubleOrNull() ?: 0.0,
                ))
            }) { Text("保存") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun BulkOperationsCard(isLoading: Boolean, appCount: Int, onApplyPolicy: (Policy) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("批量策略操作", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            val textToShow = if (isLoading) "正在扫描应用..." else "已在 /data/app 目录中找到 $appCount 个应用。将对这些应用的主用户(user 0)进行操作。"
            Text(
                text = textToShow,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { onApplyPolicy(Policy.EXEMPTED) },
                    enabled = !isLoading && appCount > 0,
                    modifier = Modifier.weight(1f)
                ) { Text(Policy.EXEMPTED.displayName) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onApplyPolicy(Policy.STANDARD) },
                    enabled = !isLoading && appCount > 0,
                    modifier = Modifier.weight(1f)
                ) { Text(Policy.STANDARD.displayName) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onApplyPolicy(Policy.STRICT) },
                    enabled = !isLoading && appCount > 0,
                    modifier = Modifier.weight(1f)
                ) { Text(Policy.STRICT.displayName) }
            }
        }
    }
}