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

            // [BUGFIX #1] 核心修正：分离数据源，UI主导数据合并
            // 步骤 1: 从前端仓库获取完整的、权威的基础应用列表
            val allInstalledApps = appInfoRepository.getAllApps(forceRefresh = true)
            val baseAppInfoMap = allInstalledApps.associateBy { it.packageName }.toMutableMap()

            // 步骤 2: 从后端获取策略配置和安全名单
            val policyConfig = daemonRepository.getAllPolicies()
            
            val finalApps: List<AppInfo>
            val finalSafetyNet: Set<String>

            if (policyConfig != null) {
                // 如果后端连接成功，合并数据
                finalSafetyNet = policyConfig.hardSafetyNet
                val policyMap = policyConfig.policies.associateBy { it.packageName to it.userId }

                // 遍历完整的应用列表，用后端的策略去“更新”它们
                finalApps = allInstalledApps.map { app ->
                    // 尝试查找完全匹配（包名+用户ID）的策略
                    val key = app.packageName to app.userId
                    policyMap[key]?.let { policy ->
                        // 找到了，更新应用信息
                        app.copy(
                            policy = Policy.fromInt(policy.policy),
                            forcePlaybackExemption = policy.forcePlaybackExempt,
                            forceNetworkExemption = policy.forceNetworkExempt
                        )
                    } ?: app //没找到，使用从AppInfoRepository加载的默认值
                }.sortedWith(compareBy<AppInfo> { it.appName.lowercase(java.util.Locale.getDefault()) }.thenBy { it.userId })

            } else {
                // 如果后端连接失败，UI依然可以显示完整的应用列表，只是策略都是默认的
                finalSafetyNet = emptySet()
                finalApps = allInstalledApps.sortedWith(compareBy<AppInfo> { it.appName.lowercase(java.util.Locale.getDefault()) }.thenBy { it.userId })
            }
            
            _uiState.update {
                it.copy(
                    isLoading = false,
                    apps = finalApps,
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
        updateAndSend(packageName, userId) { it.copy(policy = newPolicy) }
    }
    
    fun setPlaybackExemption(packageName: String, userId: Int, isExempt: Boolean) {
        updateAndSend(packageName, userId) { it.copy(forcePlaybackExemption = isExempt) }
    }

    fun setNetworkExemption(packageName: String, userId: Int, isExempt: Boolean) {
        updateAndSend(packageName, userId) { it.copy(forceNetworkExemption = isExempt) }
    }

    private fun updateAndSend(packageName: String, userId: Int, transform: (AppInfo) -> AppInfo) {
         _uiState.update { currentState ->
            var updatedApp: AppInfo? = null
            val updatedApps = currentState.apps.map { app ->
                if (app.packageName == packageName && app.userId == userId) {
                    transform(app).also { updatedApp = it }
                } else {
                    app
                }
            }
            // 确保只在找到要更新的应用时才发送IPC消息
            updatedApp?.let { daemonRepository.setPolicy(it) }
            currentState.copy(apps = updatedApps)
        }
    }

    override fun onCleared() {
        super.onCleared()
        daemonRepository.stop()
    }
}