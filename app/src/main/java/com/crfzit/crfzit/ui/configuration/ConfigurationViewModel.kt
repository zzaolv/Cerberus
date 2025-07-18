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

            val policyConfig = daemonRepository.getAllPolicies()
            val allInstalledApps = appInfoRepository.getAllApps(forceRefresh = true)
            
            if (policyConfig == null) {
                _uiState.update { it.copy(isLoading = false, apps = allInstalledApps) }
                return@launch
            }

            val baseAppInfoMap = allInstalledApps.associateBy { it.packageName }
            val mergedApps = policyConfig.policies.mapNotNull { policy ->
                baseAppInfoMap[policy.packageName]?.let { baseInfo ->
                    (if (policy.userId == 0) baseInfo else baseInfo.copy(userId = policy.userId))
                    .apply {
                        this.policy = Policy.fromInt(policy.policy)
                        this.forcePlaybackExemption = policy.forcePlaybackExempt
                        this.forceNetworkExemption = policy.forceNetworkExempt
                    }
                }
            }.sortedWith(compareBy<AppInfo> { it.appName.lowercase(java.util.Locale.getDefault()) }.thenBy { it.userId })
            
            _uiState.update {
                it.copy(
                    isLoading = false,
                    apps = mergedApps,
                    safetyNetApps = policyConfig.hardSafetyNet
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
            updatedApp?.let { daemonRepository.setPolicy(it) }
            currentState.copy(apps = updatedApps)
        }
    }

    override fun onCleared() {
        super.onCleared()
        daemonRepository.stop()
    }
}