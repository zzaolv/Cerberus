// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationViewModel.kt
package com.crfzit.crfzit.ui.configuration

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.*
import com.crfzit.crfzit.data.repository.AppInfoRepository
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// [REFACTORED] UI state now holds the entire configuration bundle
data class ConfigurationUiState(
    val isLoading: Boolean = true,
    val fullConfig: FullConfigPayload? = null,
    val appInfoMap: Map<String, AppInfo> = emptyMap(),
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
    
    fun getFilteredAndSortedApps(): List<AppPolicyPayload> {
        val state = _uiState.value
        return state.fullConfig?.policies
            ?.filter {
                val appInfo = state.appInfoMap[it.packageName] ?: return@filter false
                (state.showSystemApps || !appInfo.isSystemApp) &&
                (appInfo.appName.contains(state.searchQuery, ignoreCase = true) ||
                 appInfo.packageName.contains(state.searchQuery, ignoreCase = true))
            }
            ?.sortedWith(
                compareByDescending<AppPolicyPayload> { it.policy }
                    .thenBy { state.appInfoMap[it.packageName]?.appName?.lowercase() }
                    .thenBy { it.userId }
            ) ?: emptyList()
    }

    fun loadConfiguration() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // 1. Fetch the full configuration from the daemon
            val config = daemonRepository.getAllPolicies()
            
            // 2. Pre-fetch all app metadata for the UI
            val appInfos = appInfoRepository.getAllApps(forceRefresh = true)
                .associateBy { it.packageName }
            
            _uiState.update {
                it.copy(
                    isLoading = false,
                    fullConfig = config,
                    appInfoMap = appInfos
                )
            }
        }
    }
    
    // Example of how to update a policy and send it back
    fun setAppPolicy(packageName: String, userId: Int, newPolicyValue: Int) {
        val currentConfig = _uiState.value.fullConfig ?: return
        
        val updatedPolicies = currentConfig.policies.map {
            if (it.packageName == packageName && it.userId == userId) {
                it.copy(policy = newPolicyValue)
            } else {
                it
            }
        }
        
        val newConfig = currentConfig.copy(policies = updatedPolicies)
        
        // Update local state immediately for instant UI feedback
        _uiState.update { it.copy(fullConfig = newConfig) }
        
        // Send the entire updated config to the daemon
        daemonRepository.setPolicy(newConfig)
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onShowSystemAppsChanged(show: Boolean) {
        _uiState.update { it.copy(showSystemApps = show) }
    }

    override fun onCleared() {
        super.onCleared()
        daemonRepository.stop()
    }
}