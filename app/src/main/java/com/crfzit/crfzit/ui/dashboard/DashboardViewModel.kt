package com.crfzit.crfzit.ui.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.AppRuntimeState
import com.crfzit.crfzit.data.model.GlobalStats
import com.crfzit.crfzit.data.repository.DashboardRepository
import com.crfzit.crfzit.data.repository.UdsDashboardRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DashboardUiState(
    val globalStats: GlobalStats = GlobalStats(),
    val activeApps: List<AppRuntimeState> = emptyList(),
    val isLoading: Boolean = true,
    val isConnected: Boolean = false
)

class DashboardViewModel(
    private val repositoryOverride: DashboardRepository? = null
) : ViewModel() {

    private val repository: DashboardRepository by lazy {
        Log.i("DashboardViewModel", "Lazy repository is being initialized.")
        repositoryOverride ?: UdsDashboardRepository(viewModelScope)
    }

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        Log.i("DashboardViewModel", "ViewModel init called.")
        observeDashboardData()
    }

    private fun observeDashboardData() {
        Log.i("DashboardViewModel", "Starting to observe dashboard data...")
        viewModelScope.launch {
            // 将两个流合并
            repository.getGlobalStatsStream()
                .combine(repository.getAppRuntimeStateStream()) { stats, apps ->
                    // 成功接收到数据，更新UI状态
                    Log.d("DashboardViewModel", "Received new data from streams.")
                    DashboardUiState(
                        globalStats = stats,
                        activeApps = apps.sortedWith(compareBy({ !it.isForeground }, { it.appName })),
                        isLoading = false,
                        isConnected = true
                    )
                }
                .onStart {
                    // Flow开始收集时，立即发出加载状态
                    Log.i("DashboardViewModel", "Data stream collection started. Emitting loading state.")
                    emit(DashboardUiState(isLoading = true))
                }
                .catch { e ->
                    // 如果在收集中发生错误（例如UDS连接中断），发出失败状态
                    Log.e("DashboardViewModel", "Error in data stream: ${e.message}", e)
                    emit(DashboardUiState(isLoading = false, isConnected = false))
                }
                .collect { newState ->
                    // 将合并后的最新状态更新到 _uiState
                    _uiState.value = newState
                }
        }
    }
}