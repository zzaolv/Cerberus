// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationViewModel.kt
package com.crfzit.crfzit.ui.configuration

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.R
import com.crfzit.crfzit.data.model.*
import com.crfzit.crfzit.data.repository.AppInfoRepository
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConfigurationUiState(
    val isLoading: Boolean = true,
    val allInstalledApps: List<AppInfo> = emptyList(),
    val policies: Map<AppInstanceKey, AppPolicyPayload> = emptyMap(),
    val fullConfig: FullConfigPayload? = null,
    val searchQuery: String = "",
    val showSystemApps: Boolean = false
)

class ConfigurationViewModel(application: Application) : AndroidViewModel(application) {

    private val daemonRepository = DaemonRepository.getInstance()
    private val appInfoRepository = AppInfoRepository.getInstance(application)
    private val packageManager: PackageManager = application.packageManager

    private val _uiState = MutableStateFlow(ConfigurationUiState())
    val uiState: StateFlow<ConfigurationUiState> = _uiState.asStateFlow()

    init {
        loadConfiguration()
    }

    fun getFilteredAndSortedApps(): List<AppInfo> {
        val state = _uiState.value
        return state.allInstalledApps
            .filter { appInfo ->
                (state.showSystemApps || !appInfo.isSystemApp) &&
                        (appInfo.appName.contains(state.searchQuery, ignoreCase = true) ||
                                appInfo.packageName.contains(state.searchQuery, ignoreCase = true))
            }
            .sortedWith(
                compareByDescending<AppInfo> {
                    val key = AppInstanceKey(it.packageName, it.userId)
                    state.policies[key]?.policy ?: 0
                }.thenBy { it.appName.lowercase() }
            )
    }

    // [核心重构] 修复分身应用无法被发现的问题
    fun loadConfiguration() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // 1. 获取主空间所有可启动应用作为基础列表
            val launchableApps = getAllLaunchableApps()
            // 2. 从daemon获取完整的配置，其中可能包含分身应用的策略
            val configPayload = daemonRepository.getAllPolicies()
            val daemonPolicyMap = configPayload?.policies?.associateBy {
                AppInstanceKey(it.packageName, it.userId)
            } ?: emptyMap()

            val finalAppMap = launchableApps.associateBy {
                AppInstanceKey(it.packageName, it.userId)
            }.toMutableMap()
            
            // 3. 遍历daemon的策略，补充发现分身应用
            daemonPolicyMap.values.forEach { policy ->
                val key = AppInstanceKey(policy.packageName, policy.userId)
                // 如果这个应用实例还没在我们的列表里（通常是因为它是分身应用）
                if (!finalAppMap.containsKey(key)) {
                    // 尝试从主空间“借用”应用名称和图标
                    val baseAppInfo = appInfoRepository.getAppInfo(policy.packageName)
                    finalAppMap[key] = AppInfo(
                        packageName = policy.packageName,
                        appName = baseAppInfo?.appName ?: policy.packageName,
                        icon = baseAppInfo?.icon,
                        isSystemApp = baseAppInfo?.isSystemApp ?: false,
                        userId = policy.userId
                    )
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    allInstalledApps = finalAppMap.values.toList(),
                    policies = daemonPolicyMap,
                    fullConfig = configPayload
                )
            }
        }
    }
    
    private suspend fun getAllLaunchableApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { resolveInfo ->
                try {
                    val appInfo: ApplicationInfo = resolveInfo.activityInfo.applicationInfo
                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = appInfo.loadLabel(packageManager).toString(),
                        icon = appInfo.loadIcon(packageManager),
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        userId = 0 // queryIntentActivities 只能获取主空间，所以 userId 固定为 0
                    )
                } catch (e: Exception) {
                    null
                }
            }
    }

    fun setAppPolicy(packageName: String, userId: Int, newPolicyValue: Int) {
        val currentConfig = _uiState.value.fullConfig ?: return

        val newPolicies = currentConfig.policies.toMutableList()

        val existingPolicyIndex = newPolicies.indexOfFirst { it.packageName == packageName && it.userId == userId }

        if (existingPolicyIndex != -1) {
            newPolicies[existingPolicyIndex] = newPolicies[existingPolicyIndex].copy(policy = newPolicyValue)
        } else {
            newPolicies.add(AppPolicyPayload(packageName, userId, newPolicyValue))
        }

        val newConfig = currentConfig.copy(policies = newPolicies)

        _uiState.update {
            it.copy(
                policies = newPolicies.associateBy { p -> AppInstanceKey(p.packageName, p.userId) },
                fullConfig = newConfig
            )
        }

        daemonRepository.setPolicy(newConfig)
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onShowSystemAppsChanged(show: Boolean) {
        _uiState.update { it.copy(showSystemApps = show) }
    }

    override fun onCleared() {
        super.onCleared()
    }
}