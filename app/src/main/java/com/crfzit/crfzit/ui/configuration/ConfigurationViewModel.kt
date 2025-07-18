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
    val safetyNetApps: Set<String> = emptySet(), // 硬性安全网列表
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

    // [COMPLETE IMPLEMENTATION]
    fun loadConfiguration() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // 1. 从守护进程异步获取配置数据
            val policyConfig = daemonRepository.getAllPolicies()
            
            // 2. 从本地异步获取所有已安装应用的基本信息
            val allInstalledApps = appInfoRepository.getAllApps(forceRefresh = true)
            
            // 如果与守护进程通信失败，只显示应用列表，并结束加载状态
            if (policyConfig == null) {
                _uiState.update { it.copy(isLoading = false, apps = allInstalledApps.sortedBy { it.appName.lowercase() }) }
                return@launch
            }

            // 3. 合并守护进程的配置和本地的应用信息
            val policyMap = policyConfig.policies.associateBy { it.packageName }
            val mergedApps = allInstalledApps.map { appInfo ->
                policyMap[appInfo.packageName]?.let { policy ->
                    // 如果守护进程有此应用的配置，则应用它
                    appInfo.apply {
                        this.policy = Policy.fromInt(policy.policy)
                        this.forcePlaybackExemption = policy.forcePlaybackExempt
                        this.forceNetworkExemption = policy.forceNetworkExempt
                    }
                } ?: appInfo // 如果守护进程没有配置，则使用 appInfo 的默认值 (Policy.EXEMPTED)
            }.sortedBy { it.appName.lowercase() } // 按应用名排序
            
            // 4. 更新UI状态
            _uiState.update {
                it.copy(
                    isLoading = false,
                    apps = mergedApps,
                    safetyNetApps = policyConfig.hardSafetyNet
                )
            }
        }
    }
    
    // [COMPLETE IMPLEMENTATION]
    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    // [COMPLETE IMPLEMENTATION]
    fun onShowSystemAppsChanged(show: Boolean) {
        _uiState.update { it.copy(showSystemApps = show) }
    }

    fun setPolicy(packageName: String, newPolicy: Policy) {
        updateAndSend(packageName) { it.copy(policy = newPolicy) }
    }
    
    fun setPlaybackExemption(packageName: String, isExempt: Boolean) {
        updateAndSend(packageName) { it.copy(forcePlaybackExemption = isExempt) }
    }

    fun setNetworkExemption(packageName: String, isExempt: Boolean) {
        updateAndSend(packageName) { it.copy(forceNetworkExemption = isExempt) }
    }

    /**
     * 通用的更新函数，用于更新本地UI状态并发送指令给守护进程。
     * @param packageName 要更新的应用包名。
     * @param transform 一个lambda函数，接收旧的AppInfo，返回更新后的AppInfo。
     */
    private fun updateAndSend(packageName: String, transform: (AppInfo) -> AppInfo) {
         _uiState.update { currentState ->
            var updatedApp: AppInfo? = null
            val updatedApps = currentState.apps.map { app ->
                if (app.packageName == packageName) {
                    // 应用变换，并捕获更新后的对象
                    transform(app).also { updatedApp = it }
                } else {
                    app
                }
            }
            // 确保更新后的AppInfo不为空，然后通过仓库发送给守护进程
            updatedApp?.let { daemonRepository.setPolicy(it) }
            // 返回包含更新后列表的新状态
            currentState.copy(apps = updatedApps)
        }
    }

    override fun onCleared() {
        super.onCleared()
        daemonRepository.stop()
    }
}