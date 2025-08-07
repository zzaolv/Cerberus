// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationViewModel.kt
package com.crfzit.crfzit.ui.configuration

import android.app.Application
// import android.content.Intent // 不再需要
// import android.content.pm.ApplicationInfo // 不再需要
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.*
import com.crfzit.crfzit.data.model.Policy
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
    val showSystemApps: Boolean = false,
    val selectedAppForSheet: AppInfo? = null
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
                compareByDescending<AppInfo> { it.policy.value }
                    .thenBy { it.appName.lowercase() }
            )
    }

    fun loadConfiguration() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // --- 核心修改 START ---
            // 1. 从 AppInfoRepository 获取所有已安装的应用，而不是仅可启动的应用
            val allApps = appInfoRepository.getAllApps(forceRefresh = true) 
            // --- 核心修改 END ---

            val configPayload = daemonRepository.getAllPolicies()
            val daemonPolicyMap = configPayload?.policies?.associateBy {
                AppInstanceKey(it.packageName, it.userId)
            } ?: emptyMap()

            // 使用 allApps 作为基础数据源
            val finalAppMap = allApps.associateBy {
                AppInstanceKey(it.packageName, it.userId)
            }.toMutableMap()

            // （这部分逻辑保持不变，用于处理数据库中有但物理上可能不存在的分身应用策略）
            daemonPolicyMap.values.forEach { policy ->
                val key = AppInstanceKey(policy.packageName, policy.userId)
                if (!finalAppMap.containsKey(key) && policy.userId != 0) {
                    val baseAppInfo = appInfoRepository.getAppInfo(policy.packageName)
                    finalAppMap[key] = AppInfo(
                        packageName = policy.packageName,
                        appName = baseAppInfo?.appName ?: policy.packageName,
                        isSystemApp = baseAppInfo?.isSystemApp ?: false,
                        userId = policy.userId
                    )
                }
            }

            // （这部分逻辑保持不变，为所有应用应用从守护进程获取的策略）
            finalAppMap.values.forEach { appInfo ->
                daemonPolicyMap[AppInstanceKey(appInfo.packageName, appInfo.userId)]?.let { policy ->
                    appInfo.policy = Policy.fromInt(policy.policy)
                    // 使用您上次修改后的反转逻辑，此处保持不变
                    appInfo.forcePlaybackExemption = policy.forcePlaybackExemption ?: false
                    appInfo.forceNetworkExemption = policy.forceNetworkExemption ?: false
                    appInfo.forceLocationExemption = policy.forceLocationExemption ?: false
                    appInfo.allowTimedUnfreeze = policy.allowTimedUnfreeze ?: true
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
    
    fun setAppFullPolicy(appInfo: AppInfo) {
        val currentConfig = _uiState.value.fullConfig ?: return

        val newPolicies = currentConfig.policies.toMutableList()
        val key = AppInstanceKey(appInfo.packageName, appInfo.userId)
        val existingPolicyIndex = newPolicies.indexOfFirst { it.packageName == key.packageName && it.userId == key.userId }

        val newPolicyPayload = AppPolicyPayload(
            packageName = appInfo.packageName,
            userId = appInfo.userId,
            policy = appInfo.policy.value,
            forcePlaybackExemption = appInfo.forcePlaybackExemption,
            forceNetworkExemption = appInfo.forceNetworkExemption,
            forceLocationExemption = appInfo.forceLocationExemption,
            allowTimedUnfreeze = appInfo.allowTimedUnfreeze
        )

        if (existingPolicyIndex != -1) {
            newPolicies[existingPolicyIndex] = newPolicyPayload
        } else {
            newPolicies.add(newPolicyPayload)
        }

        val newConfig = currentConfig.copy(policies = newPolicies)
        
        _uiState.update { state ->
            val updatedApps = state.allInstalledApps.map {
                if (it.packageName == appInfo.packageName && it.userId == appInfo.userId) appInfo else it
            }
            state.copy(
                allInstalledApps = updatedApps,
                policies = newPolicies.associateBy { p -> AppInstanceKey(p.packageName, p.userId) },
                fullConfig = newConfig
            )
        }
        daemonRepository.setPolicy(newConfig)
    }

    // --- 核心修改 START ---
    // getAllLaunchableApps 方法不再需要，可以直接删除或注释掉
    /*
    private suspend fun getAllLaunchableApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        // ... old implementation ...
    }
    */
    // --- 核心修改 END ---

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onShowSystemAppsChanged(show: Boolean) {
        _uiState.update { it.copy(showSystemApps = show) }
    }
    
    fun onAppClicked(appInfo: AppInfo) {
        _uiState.update { it.copy(selectedAppForSheet = appInfo) }
    }

    fun onSheetDismiss() {
        _uiState.update { it.copy(selectedAppForSheet = null) }
    }
}