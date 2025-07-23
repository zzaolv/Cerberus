// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationViewModel.kt
package com.crfzit.crfzit.ui.configuration

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.R
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

            // 1. [数据源1] 从守护进程获取当前已知的策略配置
            val configPayload = daemonRepository.getAllPolicies()
            val daemonPolicyMap = configPayload?.policies?.associateBy {
                AppInstanceKey(it.packageName, it.userId)
            } ?: emptyMap()

            // 2. [数据源2] 获取手机上所有已安装的可启动应用 (主空间 User 0)
            val allLaunchableApps = getAllLaunchableApps()

            // 3. [合并与丰富] 以可启动应用列表为基础，合并守护进程的数据
            val finalAppList = mutableListOf<AppInfo>()
            val seenPackages = mutableSetOf<String>()

            // 3a. 遍历所有可启动的应用，这是列表的基础
            allLaunchableApps.forEach { appInfo ->
                finalAppList.add(appInfo)
                seenPackages.add(appInfo.packageName)
            }

            // 3b. 遍历守护进程返回的策略，处理分身应用
            daemonPolicyMap.values.forEach { policy ->
                // 如果是分身应用(userId != 0) 并且其主应用是可启动的
                if (policy.userId != 0 && seenPackages.contains(policy.packageName)) {
                    val baseAppInfo = allLaunchableApps.find { it.packageName == policy.packageName }
                    // 为分身应用创建一个AppInfo条目
                    finalAppList.add(
                        AppInfo(
                            packageName = policy.packageName,
                            appName = baseAppInfo?.appName ?: policy.packageName,
                            icon = baseAppInfo?.icon,
                            isSystemApp = baseAppInfo?.isSystemApp ?: false,
                            userId = policy.userId // 关键：使用分身的userId
                        )
                    )
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    allInstalledApps = finalAppList.distinctBy { app -> "${app.packageName}-${app.userId}" }, // 确保唯一性
                    policies = daemonPolicyMap,
                    fullConfig = configPayload
                )
            }
        }
    }

    /**
     * [重写辅助函数] 获取系统中所有具有 LAUNCHER 入口的应用，并包装成 AppInfo 列表。
     */
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
                        icon = appInfo.loadIcon(packageManager),
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        userId = 0 // 这里获取的都是主空间的应用
                    )
                } catch (e: Exception) {
                    null // 忽略无法加载的应用
                }
            }
    }

    fun setAppPolicy(packageName: String, userId: Int, newPolicyValue: Int) {
        val currentConfig = _uiState.value.fullConfig ?: return

        val newPolicies = currentConfig.policies.toMutableList()

        val existingPolicyIndex = newPolicies.indexOfFirst { it.packageName == packageName && it.userId == userId }

        if (existingPolicyIndex != -1) {
            // [**已修复**] 使用正确的变量名 existingPolicyIndex
            newPolicies[existingPolicyIndex] = newPolicies[existingPolicyIndex].copy(policy = newPolicyValue)
        } else {
            newPolicies.add(AppPolicyPayload(packageName, userId, newPolicyValue))
        }

        val newConfig = currentConfig.copy(policies = newPolicies)

        // 乐观更新UI
        _uiState.update {
            it.copy(
                policies = newPolicies.associateBy { p -> AppInstanceKey(p.packageName, p.userId) },
                fullConfig = newConfig
            )
        }

        // 发送到守护进程
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