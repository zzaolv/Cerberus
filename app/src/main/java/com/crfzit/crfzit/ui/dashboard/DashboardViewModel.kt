package com.crfzit.crfzit.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.AppRuntimeState
import com.crfzit.crfzit.data.model.GlobalStats
import com.crfzit.crfzit.data.repository.DashboardRepository
import com.crfzit.crfzit.data.repository.UdsDashboardRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

data class DashboardUiState(
    val globalStats: GlobalStats = GlobalStats(),
    val activeApps: List<AppRuntimeState> = emptyList(),
    val isLoading: Boolean = true,
    val isConnected: Boolean = false
)

class DashboardViewModel(
    private val injectedRepository: DashboardRepository? = null
) : ViewModel() {

    private val repository: DashboardRepository by lazy {
        injectedRepository ?: UdsDashboardRepository(viewModelScope)
    }

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeDashboardData()
    }

    private fun observeDashboardData() {
        viewModelScope.launch {
            // 添加一个初始延迟，给UDS客户端一点时间去连接
            // 如果5秒后还没有数据，就认为连接失败
            launch {
                delay(5000)
                if (_uiState.value.isLoading) {
                    _uiState.value = _uiState.value.copy(isLoading = false, isConnected = false)
                }
            }

            repository.getGlobalStatsStream()
                .combine(repository.getAppRuntimeStateStream()) { stats, apps ->
                    DashboardUiState(
                        globalStats = stats,
                        activeApps = apps.sortedWith(compareBy({ !it.isForeground }, { it.appName })), // 排序
                        isLoading = false,
                        isConnected = true
                    )
                }
                .onStart {
                    _uiState.value = DashboardUiState(isLoading = true, isConnected = false)
                }
                .catch { e ->
                    // Flow 异常时，标记为未连接
                    _uiState.value = _uiState.value.copy(isLoading = false, isConnected = false)
                }
                .collect { newState ->
                    _uiState.value = newState
                }
        }
    }
}