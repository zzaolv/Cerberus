// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationViewModel.kt
package com.crfzit.crfzit.ui.configuration

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.Policy
import com.crfzit.crfzit.data.repository.AppInfoRepository
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConfigurationUiState(
    val isLoading: Boolean = true,
    val allApps: List<AppInfo> = emptyList(),
    val searchQuery: String = "",
    val showSystemApps: Boolean = false
)

// 【核心修改】继承 AndroidViewModel 以获取 Context
class ConfigurationViewModel(application: Application) : AndroidViewModel(application) {

    // 【核心修改】获取 AppInfoRepository 单例
    private val appInfoRepository = AppInfoRepository.getInstance(application)
    
    // 【核心修改】引入 UDS Client 和 Gson
    private val udsClient = UdsClient(viewModelScope)
    private val gson = Gson()

    private val _uiState = MutableStateFlow(ConfigurationUiState())
    val uiState: StateFlow<ConfigurationUiState> = _uiState.asStateFlow()

    init {
        // 【核心修改】启动 UDS 客户端
        udsClient.start()
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            // 强制刷新加载所有应用
            appInfoRepository.loadAllInstalledApps(forceRefresh = true)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    // 从缓存中获取应用列表
                    allApps = appInfoRepository.getCachedApps().values.sortedBy { app -> app.appName }
                )
            }
        }
    }

    // 【核心修改】发送指令到守护进程
    private fun sendPolicyUpdate(appInfo: AppInfo) {
        val payload = mapOf(
            "package_name" to appInfo.packageName,
            "policy" to appInfo.policy.ordinal,
            "force_playback_exempt" to appInfo.forcePlaybackExemption,
            "force_network_exempt" to appInfo.forceNetworkExemption
        )
        val message = mapOf(
            "v" to 1,
            "type" to "cmd.set_policy",
            "payload" to payload
        )
        udsClient.sendMessage(gson.toJson(message))
    }
    
    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onShowSystemAppsChanged(show: Boolean) {
        _uiState.update { it.copy(showSystemApps = show) }
    }
    
    // 【核心修改】setPolicy 现在会发送消息
    fun setPolicy(packageName: String, policy: Policy) {
        var updatedApp: AppInfo? = null
        _uiState.update { currentState ->
            val updatedApps = currentState.allApps.map { app ->
                if (app.packageName == packageName) {
                    app.copy(policy = policy).also { updatedApp = it }
                } else {
                    app
                }
            }
            currentState.copy(allApps = updatedApps)
        }
        updatedApp?.let { sendPolicyUpdate(it) }
    }

    // 【核心修改】setPlaybackExemption 现在会发送消息
    fun setPlaybackExemption(packageName: String, isExempt: Boolean) {
        var updatedApp: AppInfo? = null
        _uiState.update { currentState ->
            val updatedApps = currentState.allApps.map { app ->
                if (app.packageName == packageName) {
                    app.copy(forcePlaybackExemption = isExempt).also { updatedApp = it }
                } else {
                    app
                }
            }
            currentState.copy(allApps = updatedApps)
        }
        updatedApp?.let { sendPolicyUpdate(it) }
    }

    // 【核心修改】setNetworkExemption 现在会发送消息
    fun setNetworkExemption(packageName: String, isExempt: Boolean) {
        var updatedApp: AppInfo? = null
        _uiState.update { currentState ->
            val updatedApps = currentState.allApps.map { app ->
                if (app.packageName == packageName) {
                    app.copy(forceNetworkExemption = isExempt).also { updatedApp = it }
                } else {
                    app
                }
            }
            currentState.copy(allApps = updatedApps)
        }
        updatedApp?.let { sendPolicyUpdate(it) }
    }

    override fun onCleared() {
        super.onCleared()
        udsClient.stop()
    }
}