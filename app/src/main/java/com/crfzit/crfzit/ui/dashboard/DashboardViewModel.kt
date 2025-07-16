// app/src/main/java/com/crfzit/crfzit/ui/dashboard/DashboardViewModel.kt
package com.crfzit.crfzit.ui.dashboard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.AppRuntimeState
import com.crfzit.crfzit.data.model.GlobalStats
import com.crfzit.crfzit.data.repository.AppInfoRepository
import com.crfzit.crfzit.data.repository.DashboardRepository
import com.crfzit.crfzit.data.repository.UdsDashboardRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

data class UiApp(
    val runtimeState: AppRuntimeState,
    val appInfo: AppInfo?
)

data class DashboardUiState(
    val globalStats: GlobalStats = GlobalStats(),
    val displayedApps: List<UiApp> = emptyList(),
    val isLoading: Boolean = true,
    val isConnected: Boolean = false,
    val showSystemApps: Boolean = false
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val udsRepository: DashboardRepository by lazy {
        UdsDashboardRepository(viewModelScope)
    }
    private val appInfoRepository = AppInfoRepository.getInstance(application)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val appInfoFlow = MutableStateFlow<Map<String, AppInfo>>(emptyMap())

    init {
        Log.i("DashboardViewModel", "ViewModel init called.")
        observeFullState()
        loadAppInfo()
    }

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
        Log.i("DashboardViewModel", "Starting to observe full dashboard state...")
        viewModelScope.launch {
            // 【核心修复】将 _uiState 直接作为 combine 的一个源
            combine(
                udsRepository.getGlobalStatsStream(),
                udsRepository.getAppRuntimeStateStream(),
                appInfoFlow,
                _uiState // 直接传递 StateFlow
            ) { stats, runtimeApps, appInfoMap, currentUiState ->

                val uiApps = if (appInfoMap.isNotEmpty()) {
                    runtimeApps.mapNotNull { runtime ->
                        appInfoMap[runtime.packageName]?.let { appInfo ->
                            UiApp(runtime, appInfo)
                        }
                    }
                } else {
                    emptyList()
                }

                val filteredAndSortedApps = uiApps
                    // 【核心修复】从 currentUiState 中获取 showSystemApps
                    .filter { currentUiState.showSystemApps || it.appInfo?.isSystemApp == false }
                    .sortedWith(
                        compareBy<UiApp> { !it.runtimeState.isForeground }
                            .thenByDescending { it.runtimeState.memUsageKb }
                    )

                // 【核心修复】使用 currentUiState.copy() 来生成新状态，确保所有字段都正确
                currentUiState.copy(
                    globalStats = stats,
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