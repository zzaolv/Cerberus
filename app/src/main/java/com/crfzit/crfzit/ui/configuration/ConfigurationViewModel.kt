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

            // 步骤 1: 从前端仓库获取完整的、权威的基础应用列表（包括所有分身）
            val allInstalledApps = appInfoRepository.getAllApps(forceRefresh = true)

            // 步骤 2: 从后端获取策略配置和安全名单
            val policyConfig = daemonRepository.getAllPolicies()
            
            val finalApps: List<AppInfo>
            val finalSafetyNet: Set<String>

            if (policyConfig != null) {
                finalSafetyNet = policyConfig.hardSafetyNet
                // 将后端的策略列表转换成一个易于查询的Map。
                // Key: 包名, Value: 策略对象。因为策略是包名全局的，所以用包名做key。
                val policyMap = policyConfig.policies.associateBy { it.packageName }

                // 步骤 3: [FIX #3] 遍历前端完整的应用列表，用后端的全局策略去“更新”它们
                finalApps = allInstalledApps.map { app ->
                    // 查找该应用包名对应的策略
                    policyMap[app.packageName]?.let { policyPayload ->
                        // 找到了，更新应用信息
                        app.copy(
                            policy = Policy.fromInt(policyPayload.policy),
                            forcePlaybackExemption = policyPayload.forcePlaybackExempt,
                            forceNetworkExemption = policyPayload.forceNetworkExempt
                        )
                    } ?: app //没找到，使用从AppInfoRepository加载的默认值 (通常是豁免)
                }.sortedWith(
                    compareBy<AppInfo> { it.appName.lowercase(java.util.Locale.getDefault()) }
                    .thenBy { it.userId } // 确保分身应用排在一起
                )

            } else {
                // 后端连接失败，UI依然可以显示完整的应用列表，只是策略都是默认的
                finalSafetyNet = emptySet()
                finalApps = allInstalledApps.sortedWith(
                    compareBy<AppInfo> { it.appName.lowercase(java.util.Locale.getDefault()) }
                    .thenBy { it.userId }
                )
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
    
    // [FIX] setPolicy 现在只关心包名和新策略，因为策略是全局的
    fun setPolicy(packageName: String, userId: Int, newPolicy: Policy) {
        // userId 在这里仅用于在UI上定位是哪个item触发了事件
        // 但发送给后台时，后台只关心包名
        updateAndSend(packageName) { it.copy(policy = newPolicy) }
    }
    
    private fun updateAndSend(packageName: String, transform: (AppInfo) -> AppInfo) {
         _uiState.update { currentState ->
            var appToSend: AppInfo? = null
            val updatedApps = currentState.apps.map { app ->
                // 更新所有同包名的实例的UI状态
                if (app.packageName == packageName) {
                    transform(app).also {
                        // 我们只需要发送一次IPC指令，所以只取第一个匹配到的app对象
                        if (appToSend == null) appToSend = it
                    }
                } else {
                    app
                }
            }
            // 确保只在找到要更新的应用时才发送IPC消息
            appToSend?.let { daemonRepository.setPolicy(it) }
            currentState.copy(apps = updatedApps)
        }
    }


    override fun onCleared() {
        super.onCleared()
        daemonRepository.stop()
    }
}