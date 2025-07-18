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
import com.crfzit.crfzit.data.model.Policy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(
    navController: NavController,
    viewModel: ConfigurationViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    
    val filteredApps = remember(uiState.apps, uiState.searchQuery, uiState.showSystemApps) {
        uiState.apps.filter { app ->
            (uiState.showSystemApps || !app.isSystemApp) &&
            (app.appName.contains(uiState.searchQuery, ignoreCase = true) ||
             app.packageName.contains(uiState.searchQuery, ignoreCase = true))
        }
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
            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredApps, key = { "${it.packageName}-${it.userId}" }) { app ->
                        val isProtected = uiState.safetyNetApps.contains(app.packageName)
                        AppPolicyItem(
                            app = app,
                            isProtected = isProtected,
                            onPolicyChange = { newPolicy ->
                                if (!isProtected) viewModel.setPolicy(app.packageName, app.userId, newPolicy)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppPolicyItem(app: AppInfo, isProtected: Boolean, onPolicyChange: (Policy) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val itemAlpha = if (isProtected) 0.6f else 1.0f

    Card(modifier = Modifier.fillMaxWidth().alpha(itemAlpha)) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .clickable(enabled = !isProtected) { showMenu = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberAsyncImagePainter(ImageRequest.Builder(LocalContext.current).data(app.icon).crossfade(true).build()),
                contentDescription = app.appName,
                modifier = Modifier.size(40.dp)
            )
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.appName, fontWeight = FontWeight.Bold)
                    if (app.userId != 0) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            painter = painterResource(id = R.drawable.ic_clone),
                            contentDescription = "分身应用 (User ${app.userId})",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
            }
            
            Box {
                val (label, icon) = getPolicyLabel(if(isProtected) Policy.EXEMPTED else app.policy)
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
        Policy.EXEMPTED -> "自由后台" to "🛡️"
        Policy.IMPORTANT -> "重要" to "✅"
        Policy.STANDARD -> "智能" to "⚙️"
        Policy.STRICT -> "严格" to "🧊"
    }
}