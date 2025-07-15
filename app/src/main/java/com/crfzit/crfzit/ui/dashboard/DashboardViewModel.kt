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

// 【修改】合并后的UI状态，包含静态的应用信息
data class UiApp(
    val runtimeState: AppRuntimeState,
    val appInfo: AppInfo? // 可能为null，如果某个包名没有对应的应用信息
)

data class DashboardUiState(
    val globalStats: GlobalStats = GlobalStats(),
    // 【修改】列表类型变为 UiApp
    val activeApps: List<UiApp> = emptyList(),
    val isLoading: Boolean = true,
    val isConnected: Boolean = false
)

// 【修改】继承自 AndroidViewModel 以获取 Application Context
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val udsRepository: DashboardRepository by lazy {
        UdsDashboardRepository(viewModelScope)
    }
    // 【新增】应用信息仓库
    private val appInfoRepository = AppInfoRepository(application)
    
    // 【新增】用于缓存应用信息的 StateFlow
    private val appInfoFlow = MutableStateFlow<Map<String, AppInfo>>(emptyMap())

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        Log.i("DashboardViewModel", "ViewModel init called.")
        loadAppInfo()
        observeDashboardData()
    }
    
    private fun loadAppInfo() {
        viewModelScope.launch {
            Log.i("DashboardViewModel", "Loading all installed app info...")
            appInfoFlow.value = appInfoRepository.getAllInstalledApps()
            Log.i("DashboardViewModel", "App info loaded. Found ${appInfoFlow.value.size} apps.")
        }
    }

    private fun observeDashboardData() {
        Log.i("DashboardViewModel", "Starting to observe dashboard data...")
        viewModelScope.launch {
            // 【核心修改】将三个流合并：全局状态、运行时状态、应用信息
            combine(
                udsRepository.getGlobalStatsStream(),
                udsRepository.getAppRuntimeStateStream(),
                appInfoFlow
            ) { stats, runtimeApps, appInfoMap ->
                
                // 将运行时状态和应用信息合并
                val uiApps = runtimeApps.map { runtime ->
                    UiApp(
                        runtimeState = runtime,
                        appInfo = appInfoMap[runtime.packageName]
                    )
                }.sortedWith(
                    // 排序逻辑：前台应用在前，然后按应用名排序
                    compareBy<UiApp> { !it.runtimeState.isForeground }
                        .thenBy { it.appInfo?.appName ?: it.runtimeState.packageName }
                )
                
                DashboardUiState(
                    globalStats = stats,
                    activeApps = uiApps,
                    isLoading = false,
                    isConnected = true
                )
            }
            .onStart {
                Log.i("DashboardViewModel", "Data stream collection started. Emitting loading state.")
                emit(DashboardUiState(isLoading = true))
            }
            .catch { e ->
                Log.e("DashboardViewModel", "Error in data stream: ${e.message}", e)
                emit(DashboardUiState(isLoading = false, isConnected = false))
            }
            .collect { newState ->
                _uiState.value = newState
            }
        }
    }
}