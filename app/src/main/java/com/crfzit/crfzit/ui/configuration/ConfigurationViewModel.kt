// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationViewModel.kt
package com.crfzit.crfzit.ui.configuration

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.Policy
import com.crfzit.crfzit.data.repository.AppInfoRepository
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ConfigurationUiState(
    val isLoading: Boolean = true,
    val apps: List<AppInfo> = emptyList(),
    val safetyNetApps: Set<String> = emptySet(),
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

    // [核心修复] 过滤和排序逻辑现在能正确处理分身应用
    fun getFilteredAndSortedApps(): List<AppInfo> {
        val state = _uiState.value
        return state.apps
            .filter { it.icon != null } // 过滤掉没有图标的应用（通常是异常情况）
            .filter { state.showSystemApps || !it.isSystemApp } // 根据开关决定是否显示系统应用
            .filter { // 根据搜索词过滤
                it.appName.contains(state.searchQuery, ignoreCase = true) ||
                        it.packageName.contains(state.searchQuery, ignoreCase = true)
            }
            .sortedWith( // 排序: 按策略等级降序 -> 应用名升序 -> 用户ID升序
                compareByDescending<AppInfo> { it.policy.value }
                    .thenBy { it.appName.lowercase() }
                    .thenBy { it.userId }
            )
    }

    // [核心修复] 重新设计了数据加载和合并逻辑，以Daemon为准，解决分身应用问题
    fun loadConfiguration() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // 1. 获取主空间的应用列表作为基础元数据（图标、名称）
            val mainUserApps = appInfoRepository.getAllApps(forceRefresh = true)
            // 2. 从Daemon获取所有已配置的应用策略（包含所有user_id）
            val policyConfig = daemonRepository.getAllPolicies()

            val finalAppsMap = mutableMapOf<Pair<String, Int>, AppInfo>()

            // 先用主空间的应用填充map
            mainUserApps.forEach { appInfo ->
                finalAppsMap[appInfo.packageName to appInfo.userId] = appInfo
            }

            val finalSafetyNet: Set<String>

            if (policyConfig != null) {
                finalSafetyNet = policyConfig.hardSafetyNet

                // 3. 遍历从Daemon获取的策略，更新或创建AppInfo对象
                policyConfig.policies.forEach { policyPayload ->
                    val key = policyPayload.packageName to policyPayload.userId
                    val existingAppInfo = finalAppsMap[key]

                    if (existingAppInfo != null) {
                        // 如果应用已存在（主用户或已处理过的分身），仅更新其策略
                        finalAppsMap[key] = existingAppInfo.copy(
                            policy = Policy.fromInt(policyPayload.policy),
                            forcePlaybackExemption = policyPayload.forcePlaybackExempt,
                            forceNetworkExemption = policyPayload.forceNetworkExempt
                        )
                    } else {
                        // 如果应用不存在（典型的分身应用），创建一个新的AppInfo对象
                        // 尝试从AppInfoRepository获取其元数据（图标、名称等）
                        val mainAppInfo = appInfoRepository.getAppInfo(policyPayload.packageName)
                        finalAppsMap[key] = AppInfo(
                            packageName = policyPayload.packageName,
                            appName = mainAppInfo?.appName ?: policyPayload.packageName, // 找不到就用包名
                            icon = mainAppInfo?.icon,
                            isSystemApp = mainAppInfo?.isSystemApp ?: false,
                            userId = policyPayload.userId, // 使用Daemon返回的正确userId
                            policy = Policy.fromInt(policyPayload.policy),
                            forcePlaybackExemption = policyPayload.forcePlaybackExempt,
                            forceNetworkExemption = policyPayload.forceNetworkExempt
                        )
                    }
                }
            } else {
                finalSafetyNet = emptySet()
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    // 将合并后的完整列表存入state
                    apps = finalAppsMap.values.toList(),
                    safetyNetApps = finalSafetyNet
                )
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onShowSystemAppsChanged(show: Boolean) {
        _uiState.update { it.copy(showSystemApps = show) }
    }

    // [核心修复] setPolicy现在能正确处理指定userId的应用
    fun setPolicy(packageName: String, userId: Int, newPolicy: Policy) {
        updateAndSend(packageName, userId) { it.copy(policy = newPolicy) }
    }

    private fun updateAndSend(packageName: String, userId: Int, transform: (AppInfo) -> AppInfo) {
        _uiState.update { currentState ->
            var appToSend: AppInfo? = null

            val updatedApps = currentState.apps.map { app ->
                if (app.packageName == packageName && app.userId == userId) {
                    transform(app).also { appToSend = it }
                } else {
                    app
                }
            }

            appToSend?.let {
                daemonRepository.setPolicy(it)
            }

            currentState.copy(apps = updatedApps)
        }
    }

    override fun onCleared() {
        super.onCleared()
        daemonRepository.stop()
    }
}