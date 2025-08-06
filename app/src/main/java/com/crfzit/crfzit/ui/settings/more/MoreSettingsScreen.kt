// app/src/main/java/com/crfzit/crfzit/ui/settings/more/MoreSettingsScreen.kt
package com.crfzit.crfzit.ui.settings.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.crfzit.crfzit.ui.configuration.Policy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSettingsScreen(
    viewModel: MoreSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showBulkDialog by remember { mutableStateOf<Policy?>(null) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("更多设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                OomPolicyCard(
                    content = uiState.adjRulesContent,
                    error = uiState.adjRulesError,
                    onReloadClick = viewModel::hotReloadOomPolicy
                )
            }
            item {
                BulkOperationsCard(
                    isLoading = uiState.isLoading,
                    appCount = uiState.dataAppPackages.size,
                    onApplyPolicy = { policy -> showBulkDialog = policy }
                )
            }
        }
    }

    if (showBulkDialog != null) {
        val policyToApply = showBulkDialog!!
        AlertDialog(
            onDismissRequest = { showBulkDialog = null },
            title = { Text("确认批量操作") },
            text = { Text("您确定要将所有 /data/app 下的应用策略设置为'${policyToApply.displayName}'吗？此操作不可逆。") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.applyBulkPolicy(policyToApply)
                        showBulkDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("确认执行")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBulkDialog = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun OomPolicyCard(content: String, error: String?, onReloadClick: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "OOM 守护策略 (adj_rules.json)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onReloadClick) {
                    Text("热重载")
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "您可以在 /data/adb/cerberus/ 目录下修改此文件，然后点击热重载使其生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                } else {
                    BeautifiedJsonText(jsonString = content)
                }
            }
        }
    }
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

@Composable
fun BeautifiedJsonText(jsonString: String) {
    val keywordColor = MaterialTheme.colorScheme.primary
    val stringColor = Color(0xFF34A853) // Green
    val numberColor = Color(0xFFF4B400) // Amber
    val defaultColor = MaterialTheme.colorScheme.onSurface

    val annotatedString = buildAnnotatedString {
        jsonString.splitToSequence("\n").forEach { line ->
            val trimmedLine = line.trim()
            val indent = line.takeWhile { it.isWhitespace() }
            append(indent)

            val keyMatch = "\"(\\w+)\":".toRegex().find(trimmedLine)
            if (keyMatch != null) {
                append("\"")
                withStyle(style = SpanStyle(color = keywordColor)) {
                    append(keyMatch.groupValues[1])
                }
                append("\":")
                append(trimmedLine.substring(keyMatch.range.last + 1))
            } else {
                 val stringMatch = "\"(.*)\"".toRegex().find(trimmedLine)
                 if (stringMatch != null) {
                     withStyle(style = SpanStyle(color = stringColor)) {
                         append(trimmedLine)
                     }
                 } else {
                     val numberMatch = "([\\d.-]+)".toRegex().find(trimmedLine)
                     if (numberMatch != null) {
                         withStyle(style = SpanStyle(color = numberColor)) {
                             append(trimmedLine)
                         }
                     } else {
                         withStyle(style = SpanStyle(color = defaultColor)) {
                              append(trimmedLine)
                         }
                     }
                 }
            }
            append("\n")
        }
    }

    Text(
        text = annotatedString,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium
    )
}