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

            // [FIX] 现在只从主用户获取应用列表
            val mainUserApps = appInfoRepository.getAllApps(forceRefresh = true)
            val policyConfig = daemonRepository.getAllPolicies()
            
            val finalApps: MutableList<AppInfo> = mainUserApps.toMutableList()
            val finalSafetyNet: Set<String>

            if (policyConfig != null) {
                finalSafetyNet = policyConfig.hardSafetyNet
                val policyMap = policyConfig.policies.associateBy { it.packageName to it.userId }

                val seenInstances = finalApps.map { it.packageName to it.userId }.toMutableSet()

                // [FIX] 遍历后端返回的策略，更新或添加应用实例到UI列表
                policyConfig.policies.forEach { policyPayload ->
                    val key = policyPayload.packageName to policyPayload.userId
                    val existingAppInfo = finalApps.find { it.packageName == key.first && it.userId == key.second }
                    
                    if (existingAppInfo != null) {
                        // 更新已存在的应用
                        val index = finalApps.indexOf(existingAppInfo)
                        finalApps[index] = existingAppInfo.copy(
                            policy = Policy.fromInt(policyPayload.policy),
                            forcePlaybackExemption = policyPayload.forcePlaybackExempt,
                            forceNetworkExemption = policyPayload.forceNetworkExempt
                        )
                    } else {
                        // 如果是分身应用，前端仓库没有，我们需要手动创建一个
                        val mainAppInfo = appInfoRepository.getAppInfo(policyPayload.packageName)
                        finalApps.add(AppInfo(
                            packageName = policyPayload.packageName,
                            appName = mainAppInfo?.appName ?: policyPayload.packageName, // 尝试用主应用的名字
                            icon = mainAppInfo?.icon, // 尝试用主应用的图标
                            isSystemApp = mainAppInfo?.isSystemApp ?: false,
                            userId = policyPayload.userId,
                            policy = Policy.fromInt(policyPayload.policy),
                            forcePlaybackExemption = policyPayload.forcePlaybackExempt,
                            forceNetworkExemption = policyPayload.forceNetworkExempt
                        ))
                    }
                }
            } else {
                finalSafetyNet = emptySet()
            }
            
            _uiState.update {
                it.copy(
                    isLoading = false,
                    apps = finalApps.sortedWith(
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
        updateAndSend(packageName, userId) { it.copy(policy = newPolicy) }
    }
    
    private fun updateAndSend(packageName: String, userId: Int, transform: (AppInfo) -> AppInfo) {
         _uiState.update { currentState ->
            var appToSend: AppInfo? = null
            val updatedApps = currentState.apps.map { app ->
                // 更新所有同包名的实例的UI状态
                if (app.packageName == packageName) {
                    // 如果是操作的那个特定实例，应用转换
                    if (app.userId == userId) {
                        transform(app).also { appToSend = it }
                    } else {
                        // 其他分身实例，只更新policy
                        transform(app.copy()).also {
                             if (appToSend == null) appToSend = it
                        }
                    }
                } else {
                    app
                }
            }
            appToSend?.let { daemonRepository.setPolicy(it) }
            currentState.copy(apps = updatedApps)
        }
    }

    override fun onCleared() {
        super.onCleared()
        daemonRepository.stop()
    }
}