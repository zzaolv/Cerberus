// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationScreen.kt
package com.crfzit.crfzit.ui.configuration

import android.app.Application
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.Policy
import com.crfzit.crfzit.navigation.Screen
import com.crfzit.crfzit.ui.icons.AppIcons
import kotlinx.coroutines.launch

class ConfigurationViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ConfigurationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ConfigurationViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(
    navController: NavController,
    viewModel: ConfigurationViewModel = viewModel(factory = ConfigurationViewModelFactory(LocalContext.current.applicationContext as Application))
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
                        val isProtected = uiState.safetyNet.contains(app.packageName)
                        AppPolicyItem(app = app, isProtected = isProtected) {
                            if (!isProtected) {
                                selectedApp = app
                                scope.launch { sheetState.show() }
                            }
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
        ) {
            AppPolicyBottomSheetContent(
                app = selectedApp!!,
                viewModel = viewModel,
                onDismiss = {
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
fun AppPolicyItem(app: AppInfo, isProtected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick, 
        modifier = Modifier.fillMaxWidth().alpha(if (isProtected) 0.6f else 1.0f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(app.icon)
                        .crossfade(true)
                        .build(),
                ),
                contentDescription = "${app.appName} icon",
                modifier = Modifier.size(40.dp)
            )
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(app.appName, fontWeight = FontWeight.Bold)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            val (label, icon) = if (isProtected) {
                "系统核心" to "🔒"
            } else {
                getPolicyLabel(app.policy)
            }
            Text(
                text = "$icon $label",
                color = if (isProtected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun AppPolicyBottomSheetContent(
    app: AppInfo,
    viewModel: ConfigurationViewModel,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .navigationBarsPadding(), 
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(app.appName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
        HorizontalDivider()
        Text("策略等级", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
             Policy.entries.forEach { policy ->
                val (label, icon) = getPolicyLabel(policy)
                Button(
                    onClick = { 
                        viewModel.setPolicy(app.packageName, policy)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
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
        Spacer(modifier = Modifier.height(8.dp))
    }
}

fun getPolicyLabel(policy: Policy): Pair<String, String> {
    return when (policy) {
        Policy.EXEMPTED -> "自由" to "🛡️"
        Policy.IMPORTANT -> "重要" to "✅"
        Policy.STANDARD -> "智能" to "⚙️"
        Policy.STRICT -> "严格" to "🧊"
    }
}