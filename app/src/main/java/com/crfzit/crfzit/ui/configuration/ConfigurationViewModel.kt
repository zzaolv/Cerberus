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

            // 1. 并行启动两个数据加载任务
            val mainUserAppsDeferred = async { 
                appInfoRepository.getAllApps(forceRefresh = true).associateBy { it.packageName } 
            }
            val configPayloadDeferred = async { daemonRepository.getAllPolicies() }

            // 2. 等待两个任务全部完成
            val mainUserAppMetaMap = mainUserAppsDeferred.await()
            val configPayload = configPayloadDeferred.await()

            // --- 从这里开始，两个数据源都已准备就绪，可以安全合并 ---

            val daemonPolicies = configPayload?.policies ?: emptyList()
            val policyMap = daemonPolicies.associateBy { AppInstanceKey(it.packageName, it.userId) }

            // 3. 手动构建最终的 AppInfo Map
            val finalAppInfoMap = mutableMapOf<AppInstanceKey, AppInfo>()

            // 3a. 添加所有主空间的应用
            mainUserAppMetaMap.values.forEach { mainApp ->
                finalAppInfoMap[AppInstanceKey(mainApp.packageName, 0)] = mainApp
            }

            // 3b. 用 Daemon 的数据进行覆盖或补充
            daemonPolicies.forEach { policy ->
                val key = AppInstanceKey(policy.packageName, policy.userId)
                if (!finalAppInfoMap.containsKey(key)) {
                    val baseAppInfo = mainUserAppMetaMap[key.packageName]
                    finalAppInfoMap[key] = AppInfo(
                        packageName = key.packageName,
                        userId = key.userId,
                        appName = baseAppInfo?.appName ?: key.packageName,
                        icon = baseAppInfo?.icon ?: ContextCompat.getDrawable(getApplication(), R.mipmap.ic_launcher),
                        isSystemApp = baseAppInfo?.isSystemApp ?: false
                    )
                }
            }
            
            val finalAppList = finalAppInfoMap.values.toList()

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