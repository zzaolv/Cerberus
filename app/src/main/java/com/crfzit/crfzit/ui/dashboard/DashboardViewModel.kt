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
    val showOnlyForeground: Boolean = false // [FIX #1] 新增状态
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val daemonRepository = DaemonRepository(viewModelScope)
    private val appInfoRepository = AppInfoRepository.getInstance(application)
    private val networkMonitor = NetworkMonitor()

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appInfoRepository.getAllApps(forceRefresh = true)

            combine(
                daemonRepository.getDashboardStream(),
                networkMonitor.getSpeedStream(),
                _uiState.map { it.showSystemApps }.distinctUntilChanged(),
                _uiState.map { it.showOnlyForeground }.distinctUntilChanged() // [FIX #1] 监听新状态
            ) { dashboardPayload, speed, showSystem, showOnlyFg -> // [FIX #1] 接收新状态
                val uiAppRuntimes = dashboardPayload.appsRuntimeState
                    .map { runtimeState ->
                        val appInfo = appInfoRepository.getAppInfo(runtimeState.packageName, runtimeState.userId)
                        UiAppRuntime(
                            runtimeState = runtimeState,
                            appName = appInfo?.appName ?: runtimeState.packageName,
                            icon = appInfo?.icon,
                            isSystem = appInfo?.isSystemApp ?: false,
                            userId = runtimeState.userId
                        )
                    }
                    .filter { showSystem || !it.isSystem }
                    // [FIX #1] 新增过滤逻辑
                    .filter { !showOnlyFg || it.runtimeState.isForeground }
                    .sortedWith(
                        compareBy<UiAppRuntime> { !it.runtimeState.isForeground }
                            .thenByDescending { it.runtimeState.cpuUsagePercent }
                            .thenByDescending { it.runtimeState.memUsageKb }
                    )

                _uiState.value.copy(
                    isConnected = true,
                    isRefreshing = false,
                    globalStats = dashboardPayload.globalStats,
                    networkSpeed = speed,
                    apps = uiAppRuntimes,
                    showSystemApps = showSystem,
                    showOnlyForeground = showOnlyFg // [FIX #1] 更新状态
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
    // [FIX #1] 新增方法来改变过滤状态
    fun onShowOnlyForegroundChanged(show: Boolean) {
        _uiState.update { it.copy(showOnlyForeground = show) }

    override fun onCleared() {
        super.onCleared()
        daemonRepository.stop()
    }
}