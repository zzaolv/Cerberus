// app/src/main/java/com/crfzit/crfzit/ui/dashboard/DashboardViewModel.kt
package com.crfzit.crfzit.ui.dashboard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.CerberusApplication
import com.crfzit.crfzit.data.model.*
import com.crfzit.crfzit.data.repository.*
import com.crfzit.crfzit.data.system.NetworkMonitor
import com.crfzit.crfzit.data.system.NetworkSpeed
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ... data classes UiApp, DashboardUiState remain the same ...
data class UiApp(
    val runtimeState: AppRuntimeState,
    val appInfo: AppInfo?
)

data class DashboardUiState(
    val globalStats: GlobalStats = GlobalStats(),
    val networkSpeed: NetworkSpeed = NetworkSpeed(),
    val displayedApps: List<UiApp> = emptyList(),
    val isLoading: Boolean = true,
    val isConnected: Boolean = false,
    val showSystemApps: Boolean = false
)


class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    // 【核心修复】从 Application 获取全局 scope，并传递给 Repository
    private val appScope = (application as CerberusApplication).applicationScope
    private val udsRepository: DashboardRepository = UdsDashboardRepository(appScope)

    private val appInfoRepository = AppInfoRepository.getInstance(application)
    private val networkMonitor = NetworkMonitor()

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()
    
    private val appInfoFlow = MutableStateFlow<Map<String, AppInfo>>(emptyMap())

    init {
        observeFullState()
        loadAppInfo()
    }

    // ... rest of the ViewModel remains the same ...
    fun onShowSystemAppsChanged(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSystemApps = show)
    }

    private fun loadAppInfo() {
        viewModelScope.launch {
            val cached = appInfoRepository.getCachedApps()
            if (cached.isNotEmpty()) {
                appInfoFlow.value = cached
            } else {
                appInfoRepository.loadAllInstalledApps()
                appInfoFlow.value = appInfoRepository.getCachedApps()
            }
            Log.i("DashboardViewModel", "App info is ready. Found ${appInfoFlow.value.size} apps.")
        }
    }

    private fun observeFullState() {
        viewModelScope.launch {
            combine(
                udsRepository.getGlobalStatsStream(),
                udsRepository.getAppRuntimeStateStream(),
                networkMonitor.getSpeedStream(),
                appInfoFlow,
                _uiState
            ) { stats, runtimeApps, speed, appInfoMap, currentUiState ->

                val uiApps = if (appInfoMap.isNotEmpty()) {
                    runtimeApps.mapNotNull { runtime ->
                        appInfoMap[runtime.packageName]?.let { appInfo ->
                            UiApp(runtime, appInfo)
                        }
                    }
                } else { emptyList() }

                val filteredAndSortedApps = uiApps
                    .filter { currentUiState.showSystemApps || it.appInfo?.isSystemApp == false }
                    .sortedWith(
                        compareBy<UiApp> { !it.runtimeState.isForeground }
                            .thenByDescending { it.runtimeState.memUsageKb }
                    )

                currentUiState.copy(
                    globalStats = stats,
                    networkSpeed = speed,
                    displayedApps = filteredAndSortedApps,
                    isLoading = false,
                    isConnected = true
                )
            }
            .onStart { emit(DashboardUiState(isLoading = true)) }
            .catch { e ->
                Log.e("DashboardViewModel", "Error in combined state flow: ${e.message}", e)
                emit(_uiState.value.copy(isLoading = false, isConnected = false))
            }
            .collect { newState ->
                _uiState.value = newState
            }
        }
    }
}