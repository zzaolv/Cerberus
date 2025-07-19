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

    fun loadConfiguration() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val mainUserApps = appInfoRepository.getAllApps(forceRefresh = true)
            val policyConfig = daemonRepository.getAllPolicies()
            
            val finalAppsMap = mutableMapOf<Pair<String, Int>, AppInfo>()

            // 1. 先用前端获取的应用信息填充基础数据 (主要是主用户 User 0)
            mainUserApps.forEach { appInfo ->
                finalAppsMap[appInfo.packageName to appInfo.userId] = appInfo
            }

            val finalSafetyNet: Set<String>

            if (policyConfig != null) {
                finalSafetyNet = policyConfig.hardSafetyNet

                // 2. 用后端返回的权威策略数据，更新或添加应用实例
                policyConfig.policies.forEach { policyPayload ->
                    val key = policyPayload.packageName to policyPayload.userId
                    val existingAppInfo = finalAppsMap[key]
                    
                    if (existingAppInfo != null) {
                        // 如果应用已存在 (通常是主应用)，更新其策略
                        finalAppsMap[key] = existingAppInfo.copy(
                            policy = Policy.fromInt(policyPayload.policy),
                            forcePlaybackExemption = policyPayload.forcePlaybackExempt,
                            forceNetworkExemption = policyPayload.forceNetworkExempt
                        )
                    } else {
                        // 如果应用不存在 (通常是分身应用)，需要创建它
                        val mainAppInfo = appInfoRepository.getAppInfo(policyPayload.packageName)
                        finalAppsMap[key] = AppInfo(
                            packageName = policyPayload.packageName,
                            appName = mainAppInfo?.appName ?: policyPayload.packageName,
                            icon = mainAppInfo?.icon,
                            isSystemApp = mainAppInfo?.isSystemApp ?: false,
                            userId = policyPayload.userId,
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
                    apps = finalAppsMap.values.sortedWith(
                        compareBy<AppInfo> { it.appName.lowercase(java.util.Locale.getDefault()) }
                        .thenBy { it.userId }
                    ),
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
    
    fun setPolicy(packageName: String, userId: Int, newPolicy: Policy) {
        // [FIX #2] 调用重构后的 updateAndSend
        updateAndSend(packageName, userId) { it.copy(policy = newPolicy) }
    }
    
    // [FIX #2] 重构此方法，使其逻辑清晰且正确
    private fun updateAndSend(packageName: String, userId: Int, transform: (AppInfo) -> AppInfo) {
         _uiState.update { currentState ->
            var appToSend: AppInfo? = null
            
            // 创建一个新的列表，只修改需要更新的那个应用
            val updatedApps = currentState.apps.map { app ->
                if (app.packageName == packageName && app.userId == userId) {
                    // 找到了要更新的特定实例，应用转换并标记以便发送
                    transform(app).also { appToSend = it }
                } else {
                    // 其他应用保持不变
                    app
                }
            }

            // 如果找到了要更新的应用，就通过 repository 发送到后端
            appToSend?.let { 
                daemonRepository.setPolicy(it)
            }

            // 更新UI状态
            currentState.copy(apps = updatedApps)
        }
    }

    override fun onCleared() {
        super.onCleared()
        daemonRepository.stop()
    }
}