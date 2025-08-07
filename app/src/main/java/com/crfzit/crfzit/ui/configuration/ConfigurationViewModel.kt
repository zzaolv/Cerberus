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
    val showSystemApps: Boolean = false,
    // [核心新增] 用于控制 BottomSheet 的状态
    val selectedAppForSheet: AppInfo? = null
)

class ConfigurationViewModel(application: Application) : AndroidViewModel(application) {

    private val daemonRepository = DaemonRepository.getInstance()
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
                compareByDescending<AppInfo> { it.policy.value }
                    .thenBy { it.appName.lowercase() }
            )
    }

    fun loadConfiguration() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val launchableApps = getAllLaunchableApps()
            val configPayload = daemonRepository.getAllPolicies()
            val daemonPolicyMap = configPayload?.policies?.associateBy {
                AppInstanceKey(it.packageName, it.userId)
            } ?: emptyMap()

            val finalAppMap = launchableApps.associateBy {
                AppInstanceKey(it.packageName, it.userId)
            }.toMutableMap()

            daemonPolicyMap.values.forEach { policy ->
                val key = AppInstanceKey(policy.packageName, policy.userId)
                if (!finalAppMap.containsKey(key) && policy.userId != 0) {
                    val baseAppInfo = appInfoRepository.getAppInfo(policy.packageName)
                    finalAppMap[key] = AppInfo(
                        packageName = policy.packageName,
                        appName = baseAppInfo?.appName ?: policy.packageName,
                        isSystemApp = baseAppInfo?.isSystemApp ?: false,
                        userId = policy.userId
                    )
                }
            }

            // [核心修改] 将后台策略数据同步到前端 AppInfo 对象中
            finalAppMap.values.forEach { appInfo ->
                daemonPolicyMap[AppInstanceKey(appInfo.packageName, appInfo.userId)]?.let { policy ->
                    appInfo.policy = Policy.fromInt(policy.policy)
                    appInfo.forcePlaybackExemption = policy.forcePlaybackExemption ?: false
                    appInfo.forceNetworkExemption = policy.forceNetworkExemption ?: false
                    appInfo.forceLocationExemption = policy.forceLocationExemption ?: false
                    appInfo.allowTimedUnfreeze = policy.allowTimedUnfreeze ?: true
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    allInstalledApps = finalAppMap.values.toList(),
                    policies = daemonPolicyMap,
                    fullConfig = configPayload
                )
            }
        }
    }
    
    // [核心新增] 新的、更全面的设置更新函数
    fun setAppFullPolicy(appInfo: AppInfo) {
        val currentConfig = _uiState.value.fullConfig ?: return

        val newPolicies = currentConfig.policies.toMutableList()
        val key = AppInstanceKey(appInfo.packageName, appInfo.userId)
        val existingPolicyIndex = newPolicies.indexOfFirst { it.packageName == key.packageName && it.userId == key.userId }

        val newPolicyPayload = AppPolicyPayload(
            packageName = appInfo.packageName,
            userId = appInfo.userId,
            policy = appInfo.policy.value,
            forcePlaybackExemption = appInfo.forcePlaybackExemption,
            forceNetworkExemption = appInfo.forceNetworkExemption,
            forceLocationExemption = appInfo.forceLocationExemption,
            allowTimedUnfreeze = appInfo.allowTimedUnfreeze
        )

        if (existingPolicyIndex != -1) {
            newPolicies[existingPolicyIndex] = newPolicyPayload
        } else {
            newPolicies.add(newPolicyPayload)
        }

        val newConfig = currentConfig.copy(policies = newPolicies)
        
        _uiState.update { state ->
            // 立即更新UI状态以获得即时反馈
            val updatedApps = state.allInstalledApps.map {
                if (it.packageName == appInfo.packageName && it.userId == appInfo.userId) appInfo else it
            }
            state.copy(
                allInstalledApps = updatedApps,
                policies = newPolicies.associateBy { p -> AppInstanceKey(p.packageName, p.userId) },
                fullConfig = newConfig
            )
        }
        // 向后台发送更新
        daemonRepository.setPolicy(newConfig)
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
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        userId = 0
                    )
                } catch (e: Exception) {
                    null
                }
            }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onShowSystemAppsChanged(show: Boolean) {
        _uiState.update { it.copy(showSystemApps = show) }
    }
    
    // [核心新增] BottomSheet 控制
    fun onAppClicked(appInfo: AppInfo) {
        _uiState.update { it.copy(selectedAppForSheet = appInfo) }
    }

    fun onSheetDismiss() {
        _uiState.update { it.copy(selectedAppForSheet = null) }
    }
}