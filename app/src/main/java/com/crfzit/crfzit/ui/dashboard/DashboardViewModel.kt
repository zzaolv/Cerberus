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
            // Pre-warm the app info cache
            appInfoRepository.getAllApps(forceRefresh = true)

            // Combine the three streams: daemon data, network speed, and UI settings
            combine(
                daemonRepository.getDashboardStream(),
                networkMonitor.getSpeedStream(),
                _uiState.map { it.showSystemApps }.distinctUntilChanged()
            ) { dashboardPayload, speed, showSystem ->
                
                // Map daemon data to UI data
                val uiAppRuntimes = dashboardPayload.appsRuntimeState
                    .mapNotNull { runtimeState ->
                        // Use the repository to get app metadata (icon, name)
                        val appInfo = appInfoRepository.getAppInfo(runtimeState.packageName)
                        // Only display if we can get metadata for it
                        appInfo?.let {
                            UiAppRuntime(
                                runtimeState = runtimeState,
                                appName = it.appName,
                                icon = it.icon,
                                isSystem = it.isSystemApp,
                                userId = runtimeState.userId
                            )
                        }
                    }
                    .filter { showSystem || !it.isSystem }
                    .sortedWith(
                        compareBy<UiAppRuntime> { !it.runtimeState.isForeground }
                            .thenByDescending { it.runtimeState.memUsageKb }
                    )

                // Update the single UI state object
                _uiState.value.copy(
                    isConnected = true,
                    isRefreshing = false,
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