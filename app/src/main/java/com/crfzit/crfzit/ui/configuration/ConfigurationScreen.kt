// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationScreen.kt
package com.crfzit.crfzit.ui.configuration

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.crfzit.crfzit.data.model.AppInstanceKey
import com.crfzit.crfzit.data.model.AppPolicyPayload

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

    val filteredApps = remember(uiState.searchQuery, uiState.showSystemApps, uiState.policies) {
        viewModel.getFilteredAndSortedApps()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("应用配置") }) }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
                        val key = AppInstanceKey(appInfo.packageName, appInfo.userId)
                        val policyPayload = uiState.policies[key]
                            ?: AppPolicyPayload(appInfo.packageName, appInfo.userId, Policy.EXEMPTED.value)

                        AppPolicyItem(
                            appInfo = appInfo,
                            policy = Policy.fromInt(policyPayload.policy),
                            onPolicyChange = { newPolicy ->
                                viewModel.setAppPolicy(appInfo.packageName, appInfo.userId, newPolicy.value)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppPolicyItem(
    appInfo: AppInfo,
    policy: Policy,
    onPolicyChange: (Policy) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val itemAlpha = if (policy == Policy.EXEMPTED) 0.7f else 1.0f

    Card(modifier = Modifier.fillMaxWidth().alpha(itemAlpha)) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .clickable { showMenu = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                // [内存优化] 与DashboardScreen同样的核心改动，使用Coil按需加载
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

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    listOf(Policy.EXEMPTED, Policy.STANDARD, Policy.STRICT).forEach { p ->
                        DropdownMenuItem(
                            text = {
                                val (policyLabel, policyIcon) = getPolicyLabelAndIcon(p)
                                Text("$policyIcon $policyLabel")
                            },
                            onClick = {
                                onPolicyChange(p)
                                showMenu = false
                            }
                        )
                    }
                }
            }
        }
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