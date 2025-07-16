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
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class ConfigurationUiState(
    val isLoading: Boolean = true,
    val allApps: List<AppInfo> = emptyList(),
    val searchQuery: String = "",
    val showSystemApps: Boolean = false,
    val safetyNet: Set<String> = emptySet() // 【新增】安全网列表
)

class ConfigurationViewModel(application: Application) : AndroidViewModel(application) {

    private val appInfoRepository = AppInfoRepository.getInstance(application)
    private val udsClient = UdsClient(viewModelScope)
    private val gson = Gson()

    private val _uiState = MutableStateFlow(ConfigurationUiState())
    val uiState: StateFlow<ConfigurationUiState> = _uiState.asStateFlow()

    init {
        udsClient.start()
        observeDaemonResponses()
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            // 1. 先加载本地安装的应用列表
            appInfoRepository.loadAllInstalledApps(forceRefresh = true)
            
            // 2. 同时向daemon请求安全网和所有应用的策略配置
            querySafetyNet()
            queryAllPolicies()
        }
    }
    
    // 【新增】监听来自daemon的响应
    private fun observeDaemonResponses() {
        viewModelScope.launch {
            udsClient.incomingMessages.filter { it.contains("\"type\":\"resp.") }.collect { jsonLine ->
                try {
                    val msg = gson.fromJson(jsonLine, Map::class.java)
                    val payloadJson = gson.toJson(msg["payload"])
                    
                    when (msg["type"]) {
                        "resp.safety_net" -> {
                            val listType = object : TypeToken<Set<String>>() {}.type
                            val safetyNetApps: Set<String> = gson.fromJson(payloadJson, listType)
                            _uiState.update { it.copy(safetyNet = safetyNetApps) }
                        }
                        "resp.all_policies" -> {
                            val listType = object : TypeToken<List<AppInfo>>() {}.type
                            val policies: List<AppInfo> = gson.fromJson(payloadJson, listType)
                            
                            val localApps = appInfoRepository.getCachedApps()
                            val mergedApps = localApps.values.map { localApp ->
                                policies.find { it.packageName == localApp.packageName }
                                    ?: localApp.copy(policy = Policy.EXEMPTED) // 默认豁免
                            }.sortedBy { it.appName }

                            _uiState.update { it.copy(allApps = mergedApps, isLoading = false) }
                        }
                    }
                } catch (e: Exception) { /* ignore */ }
            }
        }
    }
    
    // 【新增】查询方法
    private fun querySafetyNet() {
        val request = mapOf("v" to 1, "type" to "query.get_safety_net", "req_id" to UUID.randomUUID().toString())
        udsClient.sendMessage(gson.toJson(request))
    }

    private fun queryAllPolicies() {
        val request = mapOf("v" to 1, "type" to "query.get_all_policies", "req_id" to UUID.randomUUID().toString())
        udsClient.sendMessage(gson.toJson(request))
    }

    private fun sendPolicyUpdate(appInfo: AppInfo) {
        val payload = mapOf(
            "package_name" to appInfo.packageName,
            "policy" to appInfo.policy.ordinal,
            "force_playback_exempt" to appInfo.forcePlaybackExemption,
            "force_network_exempt" to appInfo.forceNetworkExemption
        )
        val message = mapOf("v" to 1, "type" to "cmd.set_policy", "payload" to payload)
        udsClient.sendMessage(gson.toJson(message))
    }
    
    fun onSearchQueryChanged(query: String) { _uiState.update { it.copy(searchQuery = query) } }
    fun onShowSystemAppsChanged(show: Boolean) { _uiState.update { it.copy(showSystemApps = show) } }
    
    fun setPolicy(packageName: String, policy: Policy) {
        updateAppInfo(packageName) { it.copy(policy = policy) }
    }

    fun setPlaybackExemption(packageName: String, isExempt: Boolean) {
        updateAppInfo(packageName) { it.copy(forcePlaybackExemption = isExempt) }
    }

    fun setNetworkExemption(packageName: String, isExempt: Boolean) {
        updateAppInfo(packageName) { it.copy(forceNetworkExemption = isExempt) }
    }

    private fun updateAppInfo(packageName: String, transform: (AppInfo) -> AppInfo) {
        var updatedApp: AppInfo? = null
        _uiState.update { currentState ->
            val updatedApps = currentState.allApps.map { app ->
                if (app.packageName == packageName) {
                    transform(app).also { updatedApp = it }
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