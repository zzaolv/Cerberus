// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationViewModel.kt
package com.crfzit.crfzit.ui.configuration

import android.app.Application
import androidx.core.content.ContextCompat // 确保这个 import 存在
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.*
import com.crfzit.crfzit.data.repository.AppInfoRepository
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.crfzit.crfzit.R // 确保 R 文件的 import 存在

data class ConfigurationUiState(
    val isLoading: Boolean = true,
    val allInstalledApps: List<AppInfo> = emptyList(),
    val policies: Map<AppInstanceKey, AppPolicyPayload> = emptyMap(),
    val fullConfig: FullConfigPayload? = null,
    val searchQuery: String = "",
    val showSystemApps: Boolean = false
)

class ConfigurationViewModel(application: Application) : AndroidViewModel(application) {

    private val daemonRepository = DaemonRepository(viewModelScope)
    private val appInfoRepository = AppInfoRepository.getInstance(application)

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

    fun loadConfiguration() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // 1. 获取主空间所有应用，作为元数据字典
            val mainUserAppsMap = appInfoRepository.getAllApps(forceRefresh = true)
                .associateBy { it.packageName }

            // 2. 从 Daemon 获取所有已知策略，这是最权威的列表
            val configPayload = daemonRepository.getAllPolicies()
            val daemonPolicies = configPayload?.policies ?: emptyList()
            val policyMap = daemonPolicies.associateBy { AppInstanceKey(it.packageName, it.userId) }

            // 3. 构建一个包含所有已知应用实例的集合
            val allKnownInstances = mutableSetOf<AppInstanceKey>()
            // 添加所有 daemon 里的实例
            daemonPolicies.forEach { allKnownInstances.add(AppInstanceKey(it.packageName, it.userId)) }
            // 添加所有主空间的应用实例 (确保即使没配置过的应用也显示)
            mainUserAppsMap.values.forEach { allKnownInstances.add(AppInstanceKey(it.packageName, 0)) }

            // 4. 基于这个完整的集合，构建最终的 AppInfo 列表
            val finalAppList = allKnownInstances.map { key ->
                val baseAppInfo = mainUserAppsMap[key.packageName]
                AppInfo(
                    packageName = key.packageName,
                    userId = key.userId,
                    appName = baseAppInfo?.appName ?: key.packageName,
                    icon = baseAppInfo?.icon ?: ContextCompat.getDrawable(getApplication(), R.mipmap.ic_launcher),
                    isSystemApp = baseAppInfo?.isSystemApp ?: false
                )
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    allInstalledApps = finalAppList.distinctBy { app -> AppInstanceKey(app.packageName, app.userId) },
                    policies = policyMap,
                    fullConfig = configPayload
                )
            }
        }
    }

    fun setAppPolicy(packageName: String, userId: Int, newPolicyValue: Int) {
        val currentConfig = _uiState.value.fullConfig ?: return
        val currentPolicies = _uiState.value.policies.toMutableMap()
        val key = AppInstanceKey(packageName, userId)

        val newPolicy = currentPolicies[key]?.copy(policy = newPolicyValue)
            ?: AppPolicyPayload(packageName, userId, newPolicyValue)
        currentPolicies[key] = newPolicy

        val newConfig = currentConfig.copy(policies = currentPolicies.values.toList())

        _uiState.update { it.copy(policies = currentPolicies, fullConfig = newConfig) }

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
        daemonRepository.stop()
    }
}