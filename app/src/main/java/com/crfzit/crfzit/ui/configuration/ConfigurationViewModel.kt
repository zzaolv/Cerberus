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
    
    // [FIX #1] 将过滤和排序逻辑封装成一个可重用的函数
    fun getFilteredAndSortedApps(): List<AppInfo> {
        val state = _uiState.value
        return state.apps
            .filter { it.icon != null } // 过滤掉没有图标的应用
            .filter { state.showSystemApps || !it.isSystemApp } // 根据开关决定是否显示系统应用
            .filter { // 根据搜索词过滤
                it.appName.contains(state.searchQuery, ignoreCase = true) ||
                it.packageName.contains(state.searchQuery, ignoreCase = true)
            }
            .sortedWith( // 按策略等级降序，再按应用名和用户ID排序
                compareByDescending<AppInfo> { it.policy.value }
                    .thenBy { it.appName.lowercase(java.util.Locale.getDefault()) }
                    .thenBy { it.userId }
            )
    }

    fun loadConfiguration() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val mainUserApps = appInfoRepository.getAllApps(forceRefresh = true)
            val policyConfig = daemonRepository.getAllPolicies()
            
            val finalAppsMap = mutableMapOf<Pair<String, Int>, AppInfo>()

            mainUserApps.forEach { appInfo ->
                finalAppsMap[appInfo.packageName to appInfo.userId] = appInfo
            }

            val finalSafetyNet: Set<String>

            if (policyConfig != null) {
                finalSafetyNet = policyConfig.hardSafetyNet

                policyConfig.policies.forEach { policyPayload ->
                    val key = policyPayload.packageName to policyPayload.userId
                    val existingAppInfo = finalAppsMap[key]
                    
                    if (existingAppInfo != null) {
                        finalAppsMap[key] = existingAppInfo.copy(
                            policy = Policy.fromInt(policyPayload.policy),
                            forcePlaybackExemption = policyPayload.forcePlaybackExempt,
                            forceNetworkExemption = policyPayload.forceNetworkExempt
                        )
                    } else {
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
                    // 不再在这里排序，交给 getFilteredAndSortedApps
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