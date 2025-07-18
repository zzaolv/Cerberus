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

data class UiAppRuntime(
    val runtimeState: AppRuntimeState,
    val appName: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val userId: Int
)

data class DashboardUiState(
    val isConnected: Boolean = false,
    val isRefreshing: Boolean = false,
    val globalStats: GlobalStats = GlobalStats(),
    val networkSpeed: NetworkSpeed = NetworkSpeed(),
    val apps: List<UiAppRuntime> = emptyList(),
    val showSystemApps: Boolean = false
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val daemonRepository = DaemonRepository(viewModelScope)
    private val appInfoRepository = AppInfoRepository.getInstance(application)
    private val networkMonitor = NetworkMonitor()

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        // [BUGFIX #3] 重构数据流，解决新应用不显示问题
        viewModelScope.launch {
            // 初始加载一次，确保基础应用信息存在
            appInfoRepository.getAllApps(forceRefresh = true)

            combine(
                daemonRepository.getDashboardStream(),
                networkMonitor.getSpeedStream(),
                _uiState.map { it.showSystemApps }.distinctUntilChanged()
            ) { dashboardPayload, speed, showSystem ->
                // [BUGFIX #3] 在combine操作符内部动态处理应用信息
                val uiAppRuntimes = dashboardPayload.appsRuntimeState
                    .mapNotNull { runtimeState ->
                        // 动态获取AppInfo，如果缓存没有会自动查询
                        val appInfo = appInfoRepository.getAppInfo(runtimeState.packageName)
                        if (appInfo != null) {
                            UiAppRuntime(
                                runtimeState = runtimeState,
                                appName = appInfo.appName,
                                icon = appInfo.icon,
                                isSystem = appInfo.isSystemApp,
                                userId = runtimeState.userId
                            )
                        } else {
                            // 如果系统就是找不到这个应用（可能已卸载但后台有残留），则不显示
                            null
                        }
                    }
                    .filter { showSystem || !it.isSystem }
                    .sortedWith(
                        compareBy<UiAppRuntime> { !it.runtimeState.isForeground }
                            .thenByDescending { it.runtimeState.memUsageKb }
                    )

                // 返回一个新的、完整的UI状态
                _uiState.value.copy(
                    isConnected = true,
                    isRefreshing = _uiState.value.isRefreshing,
                    globalStats = dashboardPayload.globalStats,
                    networkSpeed = speed,
                    apps = uiAppRuntimes,
                    showSystemApps = showSystem
                )
            }
                .catch { emit(_uiState.value.copy(isConnected = false)) }
                .collect { newState ->
                    _uiState.value = newState
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            daemonRepository.requestDashboardRefresh()
            // 增加一点延迟让UI感知到刷新状态，同时等待后台响应
            kotlinx.coroutines.delay(1000) 
            _uiState.update { it.copy(isRefreshing = false) }
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