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
    
    fun getFilteredAndSortedApps(): List<AppInfo> {
        val state = _uiState.value
        return state.apps
            .filter { it.icon != null }
            .filter { state.showSystemApps || !it.isSystemApp }
            .filter {
                it.appName.contains(state.searchQuery, ignoreCase = true) ||
                it.packageName.contains(state.searchQuery, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<AppInfo> { it.policy.value }
                    .thenBy { it.appName.lowercase(java.util.Locale.getDefault()) }
                    .thenBy { it.userId }
            )
    }

    fun loadConfiguration() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // 1. 从守护进程获取权威的全用户策略列表
            val policyConfig = daemonRepository.getAllPolicies()
            
            // 2. 将策略列表转换成UI所需的 AppInfo 列表
            val finalApps = if (policyConfig != null) {
                policyConfig.policies.map { policyPayload ->
                    // 3. 对每个应用实例，向 AppInfoRepository 请求其主应用的视觉信息
                    val baseAppInfo = appInfoRepository.getAppInfo(policyPayload.packageName)
                    
                    // 4. 融合数据：使用后端的策略和用户ID，使用前端的图标和名称
                    AppInfo(
                        packageName = policyPayload.packageName,
                        userId = policyPayload.userId,
                        policy = Policy.fromInt(policyPayload.policy),
                        forcePlaybackExemption = policyPayload.forcePlaybackExempt,
                        forceNetworkExempt = policyPayload.forceNetworkExempt,
                        
                        // 从 baseAppInfo 获取视觉信息，如果获取不到则提供默认值
                        appName = baseAppInfo?.appName ?: policyPayload.packageName,
                        icon = baseAppInfo?.icon,
                        isSystemApp = baseAppInfo?.isSystemApp ?: false
                    )
                }
            } else {
                emptyList()
            }
            
            _uiState.update {
                it.copy(
                    isLoading = false,
                    apps = finalApps,
                    safetyNetApps = policyConfig?.hardSafetyNet ?: emptySet()
                )
            }
        }
    }

    fun onSearchQueryChanged(query: String) { _uiState.update { it.copy(searchQuery = query) } }
    fun onShowSystemAppsChanged(show: Boolean) { _uiState.update { it.copy(showSystemApps = show) } }
    fun setPolicy(packageName: String, userId: Int, newPolicy: Policy) { updateAndSend(packageName, userId) { it.copy(policy = newPolicy) } }
    
    private fun updateAndSend(packageName: String, userId: Int, transform: (AppInfo) -> AppInfo) {
         _uiState.update { currentState ->
            var appToSend: AppInfo? = null
            val updatedApps = currentState.apps.map { app ->
                if (app.packageName == packageName && app.userId == userId) {
                    transform(app).also { appToSend = it }
                } else { app }
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