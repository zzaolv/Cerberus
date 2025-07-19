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
    val showSystemApps: Boolean = false,
    // [FIX #1] 默认只显示前台进程，提供更干净的视图
    val showOnlyForeground: Boolean = true
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val daemonRepository = DaemonRepository(viewModelScope)
    private val appInfoRepository = AppInfoRepository.getInstance(application)
    private val networkMonitor = NetworkMonitor()

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Pre-warm the cache
            appInfoRepository.getAllApps(forceRefresh = true)

            combine(
                daemonRepository.getDashboardStream(),
                networkMonitor.getSpeedStream(),
                _uiState.map { it.showSystemApps }.distinctUntilChanged(),
                _uiState.map { it.showOnlyForeground }.distinctUntilChanged()
            ) { dashboardPayload, speed, showSystem, showOnlyFg ->
                val uiAppRuntimes = dashboardPayload.appsRuntimeState
                    .map { runtimeState ->
                        val appInfo = appInfoRepository.getAppInfo(runtimeState.packageName)
                        UiAppRuntime(
                            runtimeState = runtimeState,
                            appName = appInfo?.appName ?: runtimeState.packageName,
                            icon = appInfo?.icon,
                            isSystem = appInfo?.isSystemApp ?: false,
                            userId = runtimeState.userId
                        )
                    }
                    // [FIX #1] 过滤掉没有图标的进程（通常是底层系统服务）
                    .filter { it.icon != null } 
                    .filter { showSystem || !it.isSystem }
                    .filter { !showOnlyFg || it.runtimeState.isForeground }
                    // [FIX #1] 调整排序：前台优先，然后按内存降序，最后按CPU降序
                    .sortedWith(
                        compareBy<UiAppRuntime> { !it.runtimeState.isForeground }
                            .thenByDescending { it.runtimeState.memUsageKb }
                            .thenByDescending { it.runtimeState.cpuUsagePercent }
                    )

                _uiState.value.copy(
                    isConnected = true,
                    isRefreshing = false,
                    globalStats = dashboardPayload.globalStats,
                    networkSpeed = speed,
                    apps = uiAppRuntimes,
                    showSystemApps = showSystem,
                    showOnlyForeground = showOnlyFg
                )
            }
                .onStart { _uiState.update { it.copy(isConnected = false) } }
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
        }
    }

    fun onShowSystemAppsChanged(show: Boolean) {
        _uiState.update { it.copy(showSystemApps = show) }
    }
    
    fun onShowOnlyForegroundChanged(show: Boolean) {
        _uiState.update { it.copy(showOnlyForeground = show) }
    }

    override fun onCleared() {
        super.onCleared()
        daemonRepository.stop()
    }
}