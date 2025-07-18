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
    val globalStats: GlobalStats = GlobalStats(),
    val networkSpeed: NetworkSpeed = NetworkSpeed(),
    val apps: List<UiAppRuntime> = emptyList(),
    val showSystemApps: Boolean = false 
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val daemonRepository = DaemonRepository(viewModelScope)
    private val appInfoRepository = AppInfoRepository.getInstance(application)
    private val networkMonitor = NetworkMonitor()
    
    private val appInfoCache = MutableStateFlow<Map<String, com.crfzit.crfzit.data.model.AppInfo>>(emptyMap())

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val appInfos = appInfoRepository.getAllApps(forceRefresh = true)
            appInfoCache.value = appInfos.associateBy { it.packageName }
        }

        viewModelScope.launch {
            combine(
                daemonRepository.getDashboardStream(),
                networkMonitor.getSpeedStream(),
                appInfoCache,
                _uiState.map { it.showSystemApps }.distinctUntilChanged()
            ) { dashboardPayload, speed, infoCache, showSystem ->
                
                val sortedAndFilteredApps = dashboardPayload.appsRuntimeState
                    .filter { it.memUsageKb > 0 || it.cpuUsagePercent > 0.1f || it.isForeground }
                    .mapNotNull { runtimeState ->
                        infoCache[runtimeState.packageName]?.let { appInfo ->
                            UiAppRuntime(
                                runtimeState = runtimeState,
                                appName = appInfo.appName,
                                icon = appInfo.icon,
                                isSystem = appInfo.isSystemApp,
                                userId = runtimeState.userId
                            )
                        }
                    }
                    .filter { showSystem || !it.isSystem }
                    .sortedWith(
                        compareBy<UiAppRuntime> { !it.runtimeState.isForeground }
                        .thenByDescending { it.runtimeState.memUsageKb }
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