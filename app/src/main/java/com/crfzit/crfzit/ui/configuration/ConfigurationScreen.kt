// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationScreen.kt
package com.crfzit.crfzit.ui.configuration

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
import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.AppPolicyPayload

// Define Policy enum directly or import from a shared location
enum class Policy(val value: Int) {
    EXEMPTED(0),
    IMPORTANT(1),
    STANDARD(2),
    STRICT(3);

    companion object {
        fun fromInt(value: Int) = entries.find { it.value == value } ?: STANDARD
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(
    navController: NavController,
    viewModel: ConfigurationViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    val filteredApps = remember(uiState) {
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
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredApps, key = { "${it.packageName}-${it.userId}" }) { appPolicy ->
                        val appInfo = uiState.appInfoMap[appPolicy.packageName]
                        // [FIX] isProtected is now part of the daemon config, not a separate list
                        val isProtected = false // Simplified, could be derived from a daemon field if needed

                        AppPolicyItem(
                            appInfo = appInfo,
                            appPolicy = appPolicy,
                            isProtected = isProtected,
                            onPolicyChange = { newPolicy ->
                                viewModel.setAppPolicy(appPolicy.packageName, appPolicy.userId, newPolicy.value)
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
    appInfo: AppInfo?,
    appPolicy: AppPolicyPayload,
    isProtected: Boolean,
    onPolicyChange: (Policy) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val itemAlpha = if (isProtected || appInfo == null) 0.6f else 1.0f

    Card(modifier = Modifier.fillMaxWidth().alpha(itemAlpha)) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .clickable(enabled = !isProtected && appInfo != null) { showMenu = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberAsyncImagePainter(
                    ImageRequest.Builder(LocalContext.current)
                        .data(appInfo?.icon)
                        .placeholder(R.drawable.ic_launcher_foreground)
                        .error(R.drawable.ic_launcher_foreground)
                        .crossfade(true).build()
                ),
                contentDescription = appInfo?.appName,
                modifier = Modifier.size(40.dp)
            )
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appInfo?.appName ?: appPolicy.packageName, fontWeight = FontWeight.Bold)
                    if (appPolicy.userId != 0) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            painter = painterResource(id = R.drawable.ic_clone),
                            contentDescription = "分身应用 (User ${appPolicy.userId})",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Text(appPolicy.packageName, style = MaterialTheme.typography.bodySmall)
            }

            Box {
                val currentPolicy = Policy.fromInt(appPolicy.policy)
                val (label, icon) = getPolicyLabel(if(isProtected) Policy.EXEMPTED else currentPolicy)
                Text(
                    text = "$icon $label",
                    color = if(isProtected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    Policy.entries.forEach { policy ->
                        DropdownMenuItem(
                            text = {
                                val (policyLabel, policyIcon) = getPolicyLabel(policy)
                                Text("$policyIcon $policyLabel")
                            },
                            onClick = {
                                onPolicyChange(policy)
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
private fun getPolicyLabel(policy: Policy): Pair<String, String> {
    return when (policy) {
        Policy.EXEMPTED -> "豁免" to "🛡️"
        Policy.IMPORTANT -> "重要" to "✅" // This policy might be deprecated in the new logic
        Policy.STANDARD -> "智能" to "⚙️"
        Policy.STRICT -> "严格" to "🧊"
    }
}