// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationViewModel.kt
package com.crfzit.crfzit.ui.configuration

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.CerberusApplication
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
    val safetyNet: Set<String> = emptySet()
)

class ConfigurationViewModel(application: Application) : AndroidViewModel(application) {

    private val appInfoRepository = AppInfoRepository.getInstance(application)
    private val appScope = (application as CerberusApplication).applicationScope
    private val udsClient = UdsClient.getInstance(appScope)
    private val gson = Gson()

    private val _uiState = MutableStateFlow(ConfigurationUiState())
    val uiState: StateFlow<ConfigurationUiState> = _uiState.asStateFlow()

    init {
        observeDaemonResponses()
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            // 确保本地应用列表已加载
            appInfoRepository.loadAllInstalledApps()
            // 向守护进程请求最新数据
            querySafetyNet()
            queryAllPolicies()
        }
    }

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
                            // 【核心修复】这是确保配置正确的关键逻辑
                            val listType = object : TypeToken<List<Map<String, Any>>>() {}.type
                            val daemonPolicies: List<Map<String, Any>> = gson.fromJson(payloadJson, listType)

                            val localApps = appInfoRepository.getCachedApps().toMutableMap()

                            // 将 daemon 的配置合并到本地应用列表
                            daemonPolicies.forEach { daemonConfig ->
                                val pkgName = daemonConfig["packageName"] as? String ?: return@forEach
                                localApps[pkgName]?.let { localApp ->
                                    // 直接修改缓存中的AppInfo对象
                                    localApp.policy = Policy.entries.getOrElse((daemonConfig["policy"] as? Double)?.toInt() ?: 0) { Policy.EXEMPTED }
                                    localApp.forcePlaybackExemption = daemonConfig["forcePlaybackExemption"] as? Boolean ?: false
                                    localApp.forceNetworkExemption = daemonConfig["forceNetworkExemption"] as? Boolean ?: false
                                }
                            }

                            _uiState.update {
                                it.copy(
                                    allApps = localApps.values.sortedBy { app -> app.appName },
                                    isLoading = false
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ConfigViewModel", "Error parsing daemon response: ${e.message}", e)
                }
            }
        }
    }

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
        // 乐观更新UI
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
        // 发送指令到守护进程
        updatedApp?.let { sendPolicyUpdate(it) }
    }
}