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
        viewModelScope.launch {
            // 启动时强制刷新一次应用列表，确保基础数据是最新的
            appInfoRepository.getAllApps(forceRefresh = true)

            combine(
                daemonRepository.getDashboardStream(),
                networkMonitor.getSpeedStream(),
                _uiState.map { it.showSystemApps }.distinctUntilChanged()
            ) { dashboardPayload, speed, showSystem ->
                // [FIX #2] 增强数据合并逻辑的鲁棒性
                val uiAppRuntimes = dashboardPayload.appsRuntimeState
                    .map { runtimeState ->
                        val appInfo = appInfoRepository.getAppInfo(runtimeState.packageName)
                        // [FIX #2] 如果 appInfo 为 null (刚安装的应用，仓库来不及更新)
                        // 仍然创建一个临时的 UiAppRuntime 对象进行显示，避免新应用消失
                        UiAppRuntime(
                            runtimeState = runtimeState,
                            appName = appInfo?.appName ?: runtimeState.packageName, // 找不到名字就用包名
                            icon = appInfo?.icon, // 找不到图标就为空
                            isSystem = appInfo?.isSystemApp ?: false,
                            userId = runtimeState.userId
                        )
                    }
                    .filter { showSystem || !it.isSystem }
                    .sortedWith(
                        compareBy<UiAppRuntime> { !it.runtimeState.isForeground }
                            .thenByDescending { it.runtimeState.cpu_usage_percent }
                            .thenByDescending { it.runtimeState.mem_usage_kb }
                    )

                _uiState.value.copy(
                    isConnected = true,
                    isRefreshing = false, // 收到新数据流，刷新状态自动结束
                    globalStats = dashboardPayload.globalStats,
                    networkSpeed = speed,
                    apps = uiAppRuntimes,
                    showSystemApps = showSystem
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
            // 移除手动延迟，让数据流驱动UI
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