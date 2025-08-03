// app/src/main/java/com/crfzit/crfzit/ui/stats/StatisticsViewModel.kt
package com.crfzit.crfzit.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.MetricsRecord
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class StatisticsUiState(
    val isLoading: Boolean = true,
    val records: List<MetricsRecord> = emptyList()
)

class StatisticsViewModel : ViewModel() {
    private val daemonRepository = DaemonRepository.getInstance()
    private val _uiState = MutableStateFlow(StatisticsUiState())
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    // [核心修改] 1. 定义一个27秒的时间窗口（单位：毫秒）
    private val timeWindowMs = 27_000L

    init {
        loadInitialStats()
        listenForNewStats()
    }

    private fun loadInitialStats() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val history = daemonRepository.getHistoryStats() ?: emptyList()

            // [核心修改] 2. 对初始加载的数据也应用时间窗口
            val filteredHistory = if (history.isNotEmpty()) {
                val latestTimestamp = history.last().timestamp
                history.filter { record ->
                    latestTimestamp - record.timestamp <= timeWindowMs
                }
            } else {
                emptyList()
            }

            _uiState.update { it.copy(isLoading = false, records = filteredHistory) }
        }
    }

    private fun listenForNewStats() {
        viewModelScope.launch {
            daemonRepository.getStatsStream().collect { newRecord ->
                _uiState.update { currentState ->
                    // [核心修改] 3. 实时维护一个滚动的时间窗口

                    // 将新记录添加到当前列表
                    val combinedList = currentState.records + newRecord

                    // 获取最新记录的时间戳作为基准
                    val latestTimestamp = newRecord.timestamp

                    // 过滤掉所有不在27秒时间窗口内的数据
                    val windowedList = combinedList.filter { record ->
                        latestTimestamp - record.timestamp <= timeWindowMs
                    }

                    currentState.copy(records = windowedList)
                }
            }
        }
    }

    // ViewModel的 onCleared 方法保持不变，无需修改
    override fun onCleared() {
        super.onCleared()
    }
}