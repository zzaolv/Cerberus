// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationScreen.kt
package com.crfzit.crfzit.ui.configuration

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.crfzit.crfzit.R
import com.crfzit.crfzit.coil.AppIcon
import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.Policy
import com.crfzit.crfzit.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(
    navController: NavController,
    viewModel: ConfigurationViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState()
    val selectedApp = uiState.selectedAppForSheet

    val filteredApps = remember(uiState.searchQuery, uiState.showSystemApps, uiState.allInstalledApps) {
        viewModel.getFilteredAndSortedApps()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用配置") },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.MoreSettings.route) }) {
                        Icon(Screen.MoreSettings.icon, contentDescription = Screen.MoreSettings.label)
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
                label = { Text("搜索应用或包名") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true
            )

            // --- UI 微调 ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable { viewModel.onShowSystemAppsChanged(!uiState.showSystemApps) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 修改文案，让用户知道列表现在更全了
                Column(modifier = Modifier.weight(1f)) {
                     Text("显示系统应用")
                     Text(
                         "列表包含所有已安装应用 (含无图标应用)", 
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant
                     )
                }
                Switch(
                    checked = uiState.showSystemApps,
                    onCheckedChange = viewModel::onShowSystemAppsChanged
                )
            }
            // --- UI 微调 END ---

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } 
            else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps, key = { "${it.packageName}-${it.userId}" }) { appInfo ->
                        AppPolicyItem(
                            appInfo = appInfo,
                            policy = appInfo.policy,
                            onClick = { viewModel.onAppClicked(appInfo) }
                        )
                    }
                }
            }
        }
    }

    if (selectedApp != null) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.onSheetDismiss() },
            sheetState = sheetState
        ) {
            AppSettingsBottomSheet(
                appInfo = selectedApp,
                onPolicyChange = { updatedApp ->
                    viewModel.setAppFullPolicy(updatedApp)
                }
            )
        }
    }
}

// ... AppPolicyItem, AppSettingsBottomSheet, 等其他 Composable 函数保持不变 ...
// (此处省略未改动的函数，实际使用时请保留它们)

@Composable
fun AppPolicyItem(
    appInfo: AppInfo,
    policy: Policy,
    onClick: () -> Unit
) {
    val itemAlpha = if (policy == Policy.EXEMPTED) 0.7f else 1.0f
    Card(modifier = Modifier
        .fillMaxWidth()
        .alpha(itemAlpha)
        .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(AppIcon(appInfo.packageName))
                        .size(73)
                        .bitmapConfig(Bitmap.Config.RGB_565)
                        .placeholder(R.drawable.ic_launcher_foreground)
                        .error(R.drawable.ic_launcher_foreground)
                        .build()
                ),
                contentDescription = appInfo.appName,
                modifier = Modifier.size(40.dp)
            )
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appInfo.appName, fontWeight = FontWeight.Bold)
                    if (appInfo.userId != 0) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            painter = painterResource(id = R.drawable.ic_clone),
                            contentDescription = "分身应用 (User ${appInfo.userId})",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Text(appInfo.packageName, style = MaterialTheme.typography.bodySmall)
            }
            Box {
                val (label, icon) = getPolicyLabelAndIcon(policy)
                Text(
                    text = "$icon $label",
                    color = if (policy == Policy.EXEMPTED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AppSettingsBottomSheet(
    appInfo: AppInfo,
    onPolicyChange: (AppInfo) -> Unit
) {
    val currentOnPolicyChange by rememberUpdatedState(onPolicyChange)
    
    var mutableAppInfo by remember { mutableStateOf(appInfo.copy()) }

    LaunchedEffect(appInfo) {
        mutableAppInfo = appInfo.copy()
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .navigationBarsPadding()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = rememberAsyncImagePainter(model = AppIcon(mutableAppInfo.packageName)),
                contentDescription = mutableAppInfo.appName,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(mutableAppInfo.appName, style = MaterialTheme.typography.titleLarge)
                Text(mutableAppInfo.packageName, style = MaterialTheme.typography.bodyMedium)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text("策略模式", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        SegmentedButtonRow(
            policy = mutableAppInfo.policy,
            onPolicySelected = { newPolicy ->
                mutableAppInfo = mutableAppInfo.copy(policy = newPolicy)
                currentOnPolicyChange(mutableAppInfo)
            }
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text("精细化豁免", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        
        ExemptionSwitch(
            title = "音频活动豁免",
            subtitle = "开启后，后台播放音频将阻止应用被冻结。",
            checked = !mutableAppInfo.forcePlaybackExemption,
            onCheckedChange = { newSwitchState ->
                mutableAppInfo = mutableAppInfo.copy(forcePlaybackExemption = !newSwitchState)
                currentOnPolicyChange(mutableAppInfo)
            }
        )
        ExemptionSwitch(
            title = "定位活动豁免",
            subtitle = "开启后，后台使用定位将阻止应用被冻结。",
            checked = !mutableAppInfo.forceLocationExemption,
            onCheckedChange = { newSwitchState ->
                mutableAppInfo = mutableAppInfo.copy(forceLocationExemption = !newSwitchState)
                currentOnPolicyChange(mutableAppInfo)
            }
        )
        ExemptionSwitch(
            title = "高网络活动豁免",
            subtitle = "开启后，后台进行高速下载等将阻止应用被冻结。",
            checked = !mutableAppInfo.forceNetworkExemption,
            onCheckedChange = { newSwitchState ->
                mutableAppInfo = mutableAppInfo.copy(forceNetworkExemption = !newSwitchState)
                currentOnPolicyChange(mutableAppInfo)
            }
        )
        
        ExemptionSwitch(
            title = "允许定时唤醒",
            subtitle = "允许此应用参与全局的定时解冻任务。",
            checked = mutableAppInfo.allowTimedUnfreeze,
            onCheckedChange = {
                mutableAppInfo = mutableAppInfo.copy(allowTimedUnfreeze = it)
                currentOnPolicyChange(mutableAppInfo)
            }
        )
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedButtonRow(policy: Policy, onPolicySelected: (Policy) -> Unit) {
    val policies = listOf(Policy.EXEMPTED, Policy.STANDARD, Policy.STRICT)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        policies.forEach { p ->
            SegmentedButton(
                shape = CircleShape,
                onClick = { onPolicySelected(p) },
                selected = p == policy,
                icon = {}
            ) {
                val (label, icon) = getPolicyLabelAndIcon(p)
                Text("$icon $label")
            }
        }
    }
}

@Composable
fun ExemptionSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun getPolicyLabelAndIcon(policy: Policy): Pair<String, String> {
    return when (policy) {
        Policy.EXEMPTED -> policy.displayName to "🛡️"
        Policy.STANDARD -> policy.displayName to "⚙️"
        Policy.STRICT -> policy.displayName to "🧊"
        else -> policy.displayName to "🤔" 
    }
}