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
import androidx.compose.material.icons.filled.MoreVert
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
import com.crfzit.crfzit.data.model.AppInstanceKey
import com.crfzit.crfzit.data.model.AppPolicyPayload
import com.crfzit.crfzit.navigation.Screen 
import com.crfzit.crfzit.ui.icons.AppIcons

enum class Policy(val value: Int, val displayName: String) {
    EXEMPTED(0, "豁免"),
    STANDARD(2, "智能"),
    STRICT(3, "严格");

    companion object {
        fun fromInt(value: Int) = entries.find { it.value == value } ?: EXEMPTED
    }
}

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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable { viewModel.onShowSystemAppsChanged(!uiState.showSystemApps) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("显示系统应用", modifier = Modifier.weight(1f))
                Switch(
                    checked = uiState.showSystemApps,
                    onCheckedChange = viewModel::onShowSystemAppsChanged
                )
            }

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
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

    // [核心新增] Bottom Sheet 逻辑
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

// [核心重构] AppPolicyItem 现在只负责显示和触发点击
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

// [核心新增] BottomSheet 的内容
@Composable
fun AppSettingsBottomSheet(
    appInfo: AppInfo,
    onPolicyChange: (AppInfo) -> Unit
) {
    // 使用 rememberUpdatedState 确保 lambda 总是最新的
    val currentOnPolicyChange by rememberUpdatedState(onPolicyChange)
    
    // 创建一个可变的本地副本，用于UI交互
    var mutableAppInfo by remember { mutableStateOf(appInfo.copy()) }

    LaunchedEffect(appInfo) {
        // 当外部传入的 appInfo 变化时，同步更新本地状态
        mutableAppInfo = appInfo.copy()
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .navigationBarsPadding() // 确保内容在导航栏之上
    ) {
        // 头部信息
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

        // 策略选择
        Text("策略模式", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        SegmentedButtonRow(
            policy = mutableAppInfo.policy,
            onPolicySelected = { newPolicy ->
                mutableAppInfo = mutableAppInfo.copy(policy = newPolicy)
                currentOnPolicyChange(mutableAppInfo)
            }
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        // 豁免开关
        Text("精细化豁免", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        ExemptionSwitch(
            title = "强制音频豁免",
            subtitle = "即使在后台播放无声或广告音频，也忽略此活动并执行冻结。",
            checked = mutableAppInfo.forcePlaybackExemption,
            onCheckedChange = {
                mutableAppInfo = mutableAppInfo.copy(forcePlaybackExemption = it)
                currentOnPolicyChange(mutableAppInfo)
            }
        )
        ExemptionSwitch(
            title = "强制定位豁免",
            subtitle = "忽略后台定位活动并执行冻结（谨慎开启）。",
            checked = mutableAppInfo.forceLocationExemption,
            onCheckedChange = {
                mutableAppInfo = mutableAppInfo.copy(forceLocationExemption = it)
                currentOnPolicyChange(mutableAppInfo)
            }
        )
        ExemptionSwitch(
            title = "强制网络豁免",
            subtitle = "忽略后台高网络活动并执行冻结（谨慎开启）。",
            checked = mutableAppInfo.forceNetworkExemption,
            onCheckedChange = {
                mutableAppInfo = mutableAppInfo.copy(forceNetworkExemption = it)
                currentOnPolicyChange(mutableAppInfo)
            }
        )
        ExemptionSwitch(
            title = "允许定时唤醒 (心跳)",
            subtitle = "允许此应用参与全局的定时解冻任务以接收消息。",
            checked = mutableAppInfo.allowTimedUnfreeze,
            onCheckedChange = {
                mutableAppInfo = mutableAppInfo.copy(allowTimedUnfreeze = it)
                currentOnPolicyChange(mutableAppInfo)
            }
        )
        Spacer(Modifier.height(16.dp))
    }
}

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
    }
}