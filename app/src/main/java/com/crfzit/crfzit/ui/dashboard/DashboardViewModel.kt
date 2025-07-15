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
    // 【修改】这个是经过过滤和排序后的最终列表
    val displayedApps: List<UiApp> = emptyList(),
    val isLoading: Boolean = true,
    val isConnected: Boolean = false,
    val appInfoLoaded: Boolean = false,
    // 【新增】UI状态，用于控制显示系统应用
    val showSystemApps: Boolean = false
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val udsRepository: DashboardRepository by lazy {
        UdsDashboardRepository(viewModelScope)
    }
    // 【修改】获取单例实例
    private val appInfoRepository = AppInfoRepository.getInstance(application)
    
    private val appInfoFlow = MutableStateFlow<Map<String, AppInfo>>(emptyMap())

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        Log.i("DashboardViewModel", "ViewModel init called.")
        observeDashboardData()
        loadAppInfo()
    }
    
    // 【新增】切换系统应用显示的方法
    fun onShowSystemAppsChanged(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSystemApps = show)
    }

    private fun loadAppInfo() {
        viewModelScope.launch {
            // 如果缓存已经是热的，直接使用
            val cached = appInfoRepository.getCachedApps()
            if (cached.isNotEmpty()) {
                Log.i("DashboardViewModel", "Using pre-warmed app info cache.")
                appInfoFlow.value = cached
                _uiState.value = _uiState.value.copy(appInfoLoaded = true)
                return@launch
            }
            
            Log.i("DashboardViewModel", "Loading all installed app info for the first time...")
            appInfoRepository.loadAllInstalledApps()
            appInfoFlow.value = appInfoRepository.getCachedApps()
            _uiState.value = _uiState.value.copy(appInfoLoaded = true)
            Log.i("DashboardViewModel", "App info loaded. Found ${appInfoFlow.value.size} apps.")
        }
    }

    private fun observeDashboardData() {
        Log.i("DashboardViewModel", "Starting to observe dashboard data...")
        viewModelScope.launch {
            // 【修改】在 combine 中加入 uiState 本身，这样 showSystemApps 状态改变时也能触发重组
            combine(
                udsRepository.getAppRuntimeStateStream(),
                appInfoFlow,
                _uiState
            ) { runtimeApps, appInfoMap, currentUiState ->
                
                val uiApps = if (appInfoMap.isNotEmpty()) {
                    runtimeApps.mapNotNull { runtime ->
                        // 如果 appInfo 不存在，直接过滤掉这个运行时状态
                        appInfoMap[runtime.packageName]?.let { appInfo ->
                            UiApp(runtime, appInfo)
                        }
                    }
                } else {
                    emptyList()
                }

                // 【核心修改】过滤和排序逻辑
                val filteredAndSortedApps = uiApps
                    .filter {
                        // 根据 showSystemApps 状态进行过滤
                        currentUiState.showSystemApps || it.appInfo?.isSystemApp == false
                    }
                    .sortedWith(
                        // 复合排序：先按前后台，再按内存从大到小
                        compareBy<UiApp> { !it.runtimeState.isForeground }
                            .thenByDescending { it.runtimeState.memUsageKb }
                    )
                
                // 返回新的 displayedApps 列表
                currentUiState.copy(displayedApps = filteredAndSortedApps)
            }
            .catch { e ->
                Log.e("DashboardViewModel", "Error in app stream: ${e.message}", e)
                emit(_uiState.value.copy(isConnected = false))
            }
            .collect { newState ->
                _uiState.value = newState
            }

            // 【修改】单独处理全局状态流，因为它和应用列表关系不大
            udsRepository.getGlobalStatsStream()
                .catch { e -> Log.e("DashboardViewModel", "Error in global stats stream: ${e.message}", e) }
                .collect { stats ->
                    _uiState.value = _uiState.value.copy(
                        globalStats = stats,
                        isLoading = false,
                        isConnected = true
                    )
                }
        }
    }
}