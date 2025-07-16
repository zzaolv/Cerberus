// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationScreen.kt
package com.crfzit.crfzit.ui.configuration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.Policy
import com.crfzit.crfzit.navigation.Screen
import com.crfzit.crfzit.ui.icons.AppIcons
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(
    navController: NavController,
    viewModel: ConfigurationViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }

    val filteredApps = remember(uiState.allApps, uiState.searchQuery, uiState.showSystemApps) {
        uiState.allApps.filter { app ->
            (uiState.showSystemApps || !app.isSystemApp) &&
            (app.appName.contains(uiState.searchQuery, ignoreCase = true) ||
             app.packageName.contains(uiState.searchQuery, ignoreCase = true))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用配置") },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.ProfileManagement.route) }) {
                        Icon(AppIcons.Style, contentDescription = "情景模式管理")
                    }
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多选项")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (uiState.showSystemApps) "隐藏系统应用" else "显示系统应用") },
                            onClick = {
                                viewModel.onShowSystemAppsChanged(!uiState.showSystemApps)
                                showMenu = false
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("搜索应用") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        AppPolicyItem(app = app) {
                            selectedApp = app
                            scope.launch { sheetState.show() }
                        }
                    }
                }
            }
        }
    }

    if (selectedApp != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedApp = null },
            sheetState = sheetState,
            // 【核心修复】移除错误的 windowInsets 参数
        ) {
            AppPolicyBottomSheetContent(
                app = selectedApp!!,
                viewModel = viewModel,
                onPolicyChange = { newPolicy ->
                    viewModel.setPolicy(selectedApp!!.packageName, newPolicy)
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            selectedApp = null
                        }
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPolicyItem(app: AppInfo, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.size(40.dp)) // Placeholder for icon
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(app.appName, fontWeight = FontWeight.Bold)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = getPolicyLabel(app.policy).first,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun AppPolicyBottomSheetContent(
    app: AppInfo,
    viewModel: ConfigurationViewModel,
    onPolicyChange: (Policy) -> Unit
) {
    // 【全面屏适配】为内容应用导航栏内边距，防止被遮挡
    Column(
        modifier = Modifier
            .padding(16.dp)
            .navigationBarsPadding(), 
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(app.appName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
        HorizontalDivider()
        Text("策略等级", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Policy.entries.forEach { policy ->
                val (label, icon) = getPolicyLabel(policy)
                Button(
                    onClick = { onPolicyChange(policy) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = app.policy != policy
                ) {
                    Text("$icon $label")
                }
            }
        }
        HorizontalDivider()
        Text("手动豁免", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("强制后台播放豁免", modifier = Modifier.weight(1f))
            Switch(
                checked = app.forcePlaybackExemption,
                onCheckedChange = { viewModel.setPlaybackExemption(app.packageName, it) }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("强制网络活动豁免", modifier = Modifier.weight(1f))
            Switch(
                checked = app.forceNetworkExemption,
                onCheckedChange = { viewModel.setNetworkExemption(app.packageName, it) }
            )
        }
        // 【全面屏适配】增加一个额外的间隔，让底部内容呼吸空间更大
        Spacer(modifier = Modifier.height(8.dp))
    }
}

fun getPolicyLabel(policy: Policy): Pair<String, String> {
    return when (policy) {
        Policy.EXEMPTED -> "自由后台" to "🛡️"
        Policy.IMPORTANT -> "重要" to "✅"
        Policy.STANDARD -> "智能" to "⚙️"
        Policy.STRICT -> "严格" to "🧊"
    }
}