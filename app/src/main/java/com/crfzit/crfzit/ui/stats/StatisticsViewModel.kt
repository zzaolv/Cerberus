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
    private val daemonRepository = DaemonRepository(viewModelScope)
    private val _uiState = MutableStateFlow(StatisticsUiState())
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    private val maxRecords = 300 // 在UI上最多显示5分钟的数据 (300 / 2秒采样)

    init {
        loadInitialStats()
        listenForNewStats()
    }

    private fun loadInitialStats() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val history = daemonRepository.getHistoryStats()?.takeLast(maxRecords) ?: emptyList()
            _uiState.update { it.copy(isLoading = false, records = history) }
        }
    }

    private fun listenForNewStats() {
        viewModelScope.launch {
            daemonRepository.getStatsStream().collect { newRecord ->
                _uiState.update { currentState ->
                    val updatedList = (currentState.records + newRecord).takeLast(maxRecords)
                    currentState.copy(records = updatedList)
                }
            }
        }
    }

    override fun onCleared() {
        daemonRepository.stop()
        super.onCleared()
    }
}