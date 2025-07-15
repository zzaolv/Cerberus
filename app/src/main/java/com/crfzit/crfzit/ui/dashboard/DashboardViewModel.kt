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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

data class UiApp(
    val runtimeState: AppRuntimeState,
    val appInfo: AppInfo?
)

data class DashboardUiState(
    val globalStats: GlobalStats = GlobalStats(),
    val activeApps: List<UiApp> = emptyList(),
    val isLoading: Boolean = true,
    val isConnected: Boolean = false,
    val appInfoLoaded: Boolean = false // 【新增】一个标志位，表示应用信息是否加载完毕
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val udsRepository: DashboardRepository by lazy {
        UdsDashboardRepository(viewModelScope)
    }
    private val appInfoRepository = AppInfoRepository(application)
    
    private val appInfoFlow = MutableStateFlow<Map<String, AppInfo>>(emptyMap())

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        Log.i("DashboardViewModel", "ViewModel init called.")
        observeDashboardData()
        loadAppInfo() // 【修改】在观察之后启动加载
    }
    
    private fun loadAppInfo() {
        viewModelScope.launch {
            Log.i("DashboardViewModel", "Loading all installed app info...")
            val allApps = appInfoRepository.getAllInstalledApps()
            appInfoFlow.value = allApps
            _uiState.value = _uiState.value.copy(appInfoLoaded = true) // 【新增】加载完成后更新标志位
            Log.i("DashboardViewModel", "App info loaded. Found ${allApps.size} apps.")
        }
    }

    private fun observeDashboardData() {
        Log.i("DashboardViewModel", "Starting to observe dashboard data...")
        viewModelScope.launch {
            combine(
                udsRepository.getGlobalStatsStream(),
                udsRepository.getAppRuntimeStateStream(),
                appInfoFlow
            ) { stats, runtimeApps, appInfoMap ->
                
                // 【修改】仅当 appInfoMap 不为空时才进行映射，避免初始空状态显示包名
                val uiApps = if (appInfoMap.isNotEmpty()) {
                    runtimeApps.map { runtime ->
                        UiApp(
                            runtimeState = runtime,
                            appInfo = appInfoMap[runtime.packageName]
                        )
                    }.sortedWith(
                        compareBy<UiApp> { !it.runtimeState.isForeground }
                            .thenBy { it.appInfo?.appName ?: it.runtimeState.packageName }
                    )
                } else {
                    emptyList()
                }
                
                DashboardUiState(
                    globalStats = stats,
                    activeApps = uiApps,
                    isLoading = false, // 收到数据流就不再是全局loading
                    isConnected = true,
                    appInfoLoaded = _uiState.value.appInfoLoaded
                )
            }
            .onStart {
                Log.i("DashboardViewModel", "Data stream collection started. Emitting loading state.")
                // 初始状态，连接和appInfo都未完成
                emit(DashboardUiState(isLoading = true, isConnected = false, appInfoLoaded = false))
            }
            .catch { e ->
                Log.e("DashboardViewModel", "Error in data stream: ${e.message}", e)
                emit(DashboardUiState(isLoading = false, isConnected = false, appInfoLoaded = _uiState.value.appInfoLoaded))
            }
            .collect { newState ->
                _uiState.value = newState
            }
        }
    }
}