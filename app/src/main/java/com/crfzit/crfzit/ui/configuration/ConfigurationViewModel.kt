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

            // 1. 从 Daemon 获取所有已知策略，这是最全的数据源
            val configPayload = daemonRepository.getAllPolicies()
            val daemonPolicies = configPayload?.policies ?: emptyList()
            val policyMap = daemonPolicies.associateBy { AppInstanceKey(it.packageName, it.userId) }

            // 2. 预加载主空间应用信息，作为元数据字典
            val mainUserAppMap = appInfoRepository.getAllApps(forceRefresh = false)
                .associateBy { it.packageName }

            // 3. 基于 Daemon 的策略列表，构建最终的应用列表
            val finalAppList = daemonPolicies.map { policy ->
                val baseAppInfo = mainUserAppMap[policy.packageName]
                AppInfo(
                    packageName = policy.packageName,
                    userId = policy.userId,
                    appName = baseAppInfo?.appName ?: policy.packageName,
                    icon = baseAppInfo?.icon ?: ContextCompat.getDrawable(getApplication(), R.mipmap.ic_launcher),
                    isSystemApp = baseAppInfo?.isSystemApp ?: false
                )
            }.toMutableList()

            // 4. 添加那些在主空间存在，但在 Daemon 策略中还未配置的应用
            val daemonPackages = daemonPolicies.map { it.packageName }.toSet()
            mainUserAppMap.values.forEach { mainApp ->
                if (!daemonPackages.contains(mainApp.packageName)) {
                    // 确保列表中至少有主空间的应用实例
                    val key = AppInstanceKey(mainApp.packageName, 0)
                    if (finalAppList.none { AppInstanceKey(it.packageName, it.userId) == key }) {
                        finalAppList.add(mainApp)
                    }
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    // 使用 distinctBy 确保最终列表的唯一性
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