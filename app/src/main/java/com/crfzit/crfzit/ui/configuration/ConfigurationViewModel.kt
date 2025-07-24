// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationViewModel.kt
package com.crfzit.crfzit.ui.configuration

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.*
import com.crfzit.crfzit.data.repository.AppInfoRepository
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConfigurationUiState(
    val isLoading: Boolean = true,
    val allInstalledApps: List<AppInfo> = emptyList(),
    val policies: Map<AppInstanceKey, AppPolicyPayload> = emptyMap(),
    val fullConfig: FullConfigPayload? = null,
    val searchQuery: String = "",
    val showSystemApps: Boolean = false
)

class ConfigurationViewModel(application: Application) : AndroidViewModel(application) {

    private val daemonRepository = DaemonRepository(viewModelScope)
    private val appInfoRepository = AppInfoRepository.getInstance(application)
    private val packageManager: PackageManager = application.packageManager

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

            val configPayload = daemonRepository.getAllPolicies()
            val daemonPolicyMap = configPayload?.policies?.associateBy {
                AppInstanceKey(it.packageName, it.userId)
            } ?: emptyMap()

            val allLaunchableApps = getAllLaunchableApps()

            val finalAppList = mutableListOf<AppInfo>()
            val seenPackages = mutableSetOf<String>()

            allLaunchableApps.forEach { appInfo ->
                finalAppList.add(appInfo)
                seenPackages.add(appInfo.packageName)
            }

            daemonPolicyMap.values.forEach { policy ->
                if (policy.userId != 0 && seenPackages.contains(policy.packageName)) {
                    val baseAppInfo = allLaunchableApps.find { it.packageName == policy.packageName }
                    // 为分身应用创建一个AppInfo条目
                    finalAppList.add(
                        AppInfo(
                            packageName = policy.packageName,
                            appName = baseAppInfo?.appName ?: policy.packageName,
                            // [MEM_OPT] 为分身应用也传递ApplicationInfo
                            applicationInfo = baseAppInfo?.applicationInfo,
                            isSystemApp = baseAppInfo?.isSystemApp ?: false,
                            userId = policy.userId
                        )
                    )
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    allInstalledApps = finalAppList.distinctBy { app -> "${app.packageName}-${app.userId}" },
                    policies = daemonPolicyMap,
                    fullConfig = configPayload
                )
            }
        }
    }

    private suspend fun getAllLaunchableApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { resolveInfo ->
                try {
                    val appInfo: ApplicationInfo = resolveInfo.activityInfo.applicationInfo
                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = appInfo.loadLabel(packageManager).toString(),
                        // [MEM_OPT] 同样，只保存ApplicationInfo
                        applicationInfo = appInfo,
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        userId = 0
                    )
                } catch (e: Exception) {
                    null
                }
            }
    }

    fun setAppPolicy(packageName: String, userId: Int, newPolicyValue: Int) {
        val currentConfig = _uiState.value.fullConfig ?: return
        val newPolicies = currentConfig.policies.toMutableList()
        val existingPolicyIndex = newPolicies.indexOfFirst { it.packageName == packageName && it.userId == userId }

        if (existingPolicyIndex != -1) {
            newPolicies[existingPolicyIndex] = newPolicies[existingPolicyIndex].copy(policy = newPolicyValue)
        } else {
            newPolicies.add(AppPolicyPayload(packageName, userId, newPolicyValue))
        }

        val newConfig = currentConfig.copy(policies = newPolicies)

        _uiState.update {
            it.copy(
                policies = newPolicies.associateBy { p -> AppInstanceKey(p.packageName, p.userId) },
                fullConfig = newConfig
            )
        }

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