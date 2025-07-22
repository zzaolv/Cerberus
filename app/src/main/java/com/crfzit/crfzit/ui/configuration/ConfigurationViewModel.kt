// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationViewModel.kt
package com.crfzit.crfzit.ui.configuration

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.*
import com.crfzit.crfzit.data.repository.AppInfoRepository
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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

            // 步骤 1: 主要数据源变为 Daemon
            val configPayload = daemonRepository.getAllPolicies()
            val daemonPolicies = configPayload?.policies ?: emptyList()
            val policyMap = daemonPolicies
                .associateBy { AppInstanceKey(it.packageName, it.userId) }

            // 步骤 2: 获取主空间应用作为元数据基础
            // forceRefresh = false, 优先使用缓存
            val mainUserApps = appInfoRepository.getAllApps(forceRefresh = false)
            val finalAppList = mainUserApps.toMutableList()
            
            // 已知应用实例的集合，用于快速查找
            val knownInstances = mainUserApps.map { AppInstanceKey(it.packageName, it.userId) }.toMutableSet()
            
            // 步骤 3: 遍历 Daemon 的策略，找出主空间没有的应用 (即分身应用)
            daemonPolicies.forEach { policy ->
                val key = AppInstanceKey(policy.packageName, policy.userId)
                if (!knownInstances.contains(key)) {
                    // 这是一个分身应用，且主空间不存在，为它创建降级版 AppInfo
                    val baseAppInfo = appInfoRepository.getAppInfo(policy.packageName) // 尝试获取主空间图标和名称
                    
                    val syntheticAppInfo = AppInfo(
                        packageName = policy.packageName,
                        userId = policy.userId,
                        // 优先使用主空间的名称和图标，如果找不到则降级
                        appName = baseAppInfo?.appName ?: policy.packageName,
                        icon = baseAppInfo?.icon ?: ContextCompat.getDrawable(getApplication(), R.mipmap.ic_launcher),
                        isSystemApp = baseAppInfo?.isSystemApp ?: false // 沿用主空间应用的系统属性
                    )
                    finalAppList.add(syntheticAppInfo)
                    knownInstances.add(key) // 添加到已知集合，防止重复
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    allInstalledApps = finalAppList,
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