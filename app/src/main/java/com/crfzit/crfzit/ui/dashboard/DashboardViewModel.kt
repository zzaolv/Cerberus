// app/src/main/java/com/crfzit/crfzit/ui/dashboard/DashboardViewModel.kt
package com.crfzit.crfzit.ui.dashboard

import android.app.Application
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.AppRuntimeState
import com.crfzit.crfzit.data.model.GlobalStats
import com.crfzit.crfzit.data.repository.AppInfoRepository
import com.crfzit.crfzit.data.repository.DaemonRepository
import com.crfzit.crfzit.data.system.NetworkMonitor
import com.crfzit.crfzit.data.system.NetworkSpeed
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// [FIX 1.1] UiAppRuntime现在包含完整的应用名和图标
data class UiAppRuntime(
    val runtimeState: AppRuntimeState,
    val appName: String,
    val icon: Drawable?,
    val isSystem: Boolean,
)

data class DashboardUiState(
    val isConnected: Boolean = false,
    val globalStats: GlobalStats = GlobalStats(),
    val networkSpeed: NetworkSpeed = NetworkSpeed(),
    val apps: List<UiAppRuntime> = emptyList(),
    // [FIX 1.3] 默认不显示系统应用
    val showSystemApps: Boolean = false
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val daemonRepository = DaemonRepository(viewModelScope)
    private val appInfoRepository = AppInfoRepository.getInstance(application)
    private val networkMonitor = NetworkMonitor()

    // [FIX 1.1] 缓存AppInfo对象，而不仅仅是布尔值
    private val appInfoCache = MutableStateFlow<Map<String, com.crfzit.crfzit.data.model.AppInfo>>(emptyMap())

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        // 1. 预加载应用列表信息到缓存
        viewModelScope.launch {
            val appInfos = appInfoRepository.getAllApps(forceRefresh = true)
            appInfoCache.value = appInfos.associateBy { it.packageName }
        }

        // 2. 组合所有数据流来构建完整的UI状态
        viewModelScope.launch {
            combine(
                daemonRepository.getDashboardStream(),
                networkMonitor.getSpeedStream(),
                appInfoCache,
                _uiState.map { it.showSystemApps }.distinctUntilChanged() // 只在开关变化时触发
            ) { dashboardPayload, speed, infoCache, showSystem ->

                // [FIX] 完整的过滤和排序逻辑
                val sortedAndFilteredApps = dashboardPayload.appsRuntimeState
                    // [FIX 1.2] 只显示有前台活动或正在冻结过程中的应用
                    .filter { it.isForeground || it.displayStatus.uppercase() == "AWAITING_FREEZE" || it.displayStatus.uppercase() == "FROZEN" }
                    .mapNotNull { runtimeState ->
                        // [FIX 1.1] 合并数据，如果缓存没有则不显示该项
                        infoCache[runtimeState.packageName]?.let { appInfo ->
                            UiAppRuntime(
                                runtimeState = runtimeState,
                                appName = appInfo.appName,
                                icon = appInfo.icon,
                                isSystem = appInfo.isSystemApp
                            )
                        }
                    }
                    // [FIX 1.3] 根据开关过滤系统应用
                    .filter { showSystem || !it.isSystem }
                    .sortedWith(
                        compareBy<UiAppRuntime> { !it.runtimeState.isForeground } // 前台应用优先
                        .thenByDescending { it.runtimeState.memUsageKb } // 然后按内存降序
                    )

                DashboardUiState(
                    isConnected = true,
                    globalStats = dashboardPayload.globalStats,
                    networkSpeed = speed,
                    apps = sortedAndFilteredApps,
                    showSystemApps = showSystem
                )
            }
            .catch { emit(_uiState.value.copy(isConnected = false)) }
            .collect { newState ->
                _uiState.value = newState
            }
        }
    }

    fun onShowSystemAppsChanged(show: Boolean) {
        _uiState.update { it.copy(showSystemApps = show) }
    }

    override fun onCleared() {
        super.onCleared()
        daemonRepository.stop()
    }
}