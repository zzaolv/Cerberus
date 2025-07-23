// app/src/main/java/com/crfzit/crfzit/ui/configuration/ConfigurationViewModel.kt
package com.crfzit.crfzit.ui.configuration

import android.app.Application
import android.content.Intent
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

            // 1. 获取守护进程的完整配置，这包含了所有被监控的应用实例
            val configPayload = daemonRepository.getAllPolicies()
            // 2. 获取主空间(User 0)所有可启动的应用包名列表
            val launchablePackages = getLaunchablePackages()

            // 3. 获取主空间(User 0)所有应用的基础元数据（图标、名称等）
            val mainUserAppMetaMap = appInfoRepository.getAllApps(forceRefresh = true)
                .associateBy { it.packageName }

            // --- 数据准备完毕，开始安全合并与过滤 ---

            val daemonPolicies = configPayload?.policies ?: emptyList()
            val policyMap = daemonPolicies.associateBy { AppInstanceKey(it.packageName, it.userId) }

            val finalAppInfoMap = mutableMapOf<AppInstanceKey, AppInfo>()

            // 4. 遍历守护进程上报的所有应用策略
            daemonPolicies.forEach { policy ->
                // [核心过滤逻辑] 只有当一个应用是可启动的，我们才考虑将它显示在配置页
                if (launchablePackages.contains(policy.packageName)) {
                    val key = AppInstanceKey(policy.packageName, policy.userId)
                    val baseAppInfo = mainUserAppMetaMap[policy.packageName]

                    // 为这个可启动的应用实例（可能是主应用或分身）创建或更新 AppInfo
                    finalAppInfoMap[key] = AppInfo(
                        packageName = key.packageName,
                        userId = key.userId,
                        appName = baseAppInfo?.appName ?: key.packageName,
                        icon = baseAppInfo?.icon ?: ContextCompat.getDrawable(getApplication(), R.mipmap.ic_launcher),
                        isSystemApp = baseAppInfo?.isSystemApp ?: false
                    )
                }
            }

            val finalAppList = finalAppInfoMap.values.toList()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    allInstalledApps = finalAppList,
                    policies = policyMap,
                    fullConfig = configPayload
                )
            }
        }
    }

    /**
     * [新增辅助函数] 获取系统中所有具有 LAUNCHER 入口的应用包名。
     * 这是一个耗时操作，因此在IO线程中执行。
     */
    private suspend fun getLaunchablePackages(): Set<String> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .toSet()
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