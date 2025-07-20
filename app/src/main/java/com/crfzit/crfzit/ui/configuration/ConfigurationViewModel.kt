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

data class ConfigurationUiState(
    val isLoading: Boolean = true,
    // The master list of all installed apps, including clones treated as base apps
    val allInstalledApps: List<AppInfo> = emptyList(),
    // The map of policies from the daemon, this is the source of truth for settings
    val policies: Map<AppInstanceKey, AppPolicyPayload> = emptyMap(),
    // The full config object for master/exempt switches
    val fullConfig: FullConfigPayload? = null,
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
        return state.allInstalledApps
            .filter { appInfo ->
                (state.showSystemApps || !appInfo.isSystemApp) &&
                        (appInfo.appName.contains(state.searchQuery, ignoreCase = true) ||
                                appInfo.packageName.contains(state.searchQuery, ignoreCase = true))
            }
            // Sort by policy (higher policy first), then by app name
            .sortedWith(
                compareByDescending<AppInfo> {
                    val key = AppInstanceKey(it.packageName, it.userId)
                    state.policies[key]?.policy ?: 0
                }.thenBy { it.appName.lowercase() }
            )
    }

    fun loadConfiguration() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // 1. Get ALL installed apps from the system as the base list.
            val allApps = appInfoRepository.getAllApps(forceRefresh = true)

            // 2. Get the current policies from the daemon.
            val configPayload = daemonRepository.getAllPolicies()
            val policyMap = configPayload?.policies
                ?.associateBy { AppInstanceKey(it.packageName, it.userId) } ?: emptyMap()

            // 3. Discover clone apps that might not be in the base list
            val finalAppList = allApps.toMutableList()
            val knownPackages = allApps.map { it.packageName }.toSet()

            policyMap.values.forEach { policy ->
                if (!knownPackages.contains(policy.packageName) || policy.userId != 0) {
                    val existing = finalAppList.any { it.packageName == policy.packageName && it.userId == policy.userId }
                    if (!existing) {
                        // This is a clone app not in our list, create a placeholder
                        val baseApp = appInfoRepository.getAppInfo(policy.packageName)
                        finalAppList.add(
                            AppInfo(
                                packageName = policy.packageName,
                                appName = baseApp?.appName ?: policy.packageName,
                                icon = baseApp?.icon,
                                isSystemApp = baseApp?.isSystemApp ?: false,
                                userId = policy.userId // Crucially, use the correct userId
                            )
                        )
                    }
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    allInstalledApps = finalAppList.distinctBy { "${it.packageName}-${it.userId}" },
                    policies = policyMap,
                    fullConfig = configPayload
                )
            }
        }
    }

    fun setAppPolicy(packageName: String, userId: Int, newPolicyValue: Int) {
        val currentConfig = _uiState.value.fullConfig ?: return
        val currentPolicies = _uiState.value.policies.toMutableMap()
        val key = AppInstanceKey(packageName, userId)

        val newPolicy = currentPolicies[key]?.copy(policy = newPolicyValue)
            ?: AppPolicyPayload(packageName, userId, newPolicyValue)
        currentPolicies[key] = newPolicy

        val newConfig = currentConfig.copy(policies = currentPolicies.values.toList())

        _uiState.update { it.copy(policies = currentPolicies, fullConfig = newConfig) }

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