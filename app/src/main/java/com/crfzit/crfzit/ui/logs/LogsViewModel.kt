// app/src/main/java/com/crfzit/crfzit/ui/logs/LogsViewModel.kt
package com.crfzit.crfzit.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.LogEntry
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LogsUiState(
    val isLoading: Boolean = true,
    val logs: List<LogEntry> = emptyList()
)

class LogsViewModel : ViewModel() {

    private val daemonRepository = DaemonRepository(viewModelScope)
    
    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    init {
        loadInitialLogs()
        listenForNewLogs()
    }
    
    private fun loadInitialLogs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val history = daemonRepository.getAllLogs() ?: emptyList()
            _uiState.update { it.copy(isLoading = false, logs = history) }
        }
    }

    private fun listenForNewLogs() {
        viewModelScope.launch {
            daemonRepository.getLogStream().collect { newLog ->
                _uiState.update { currentState ->
                    // 将新日志添加到列表末尾，LazyColumn反向布局会显示在顶部
                    currentState.copy(logs = currentState.logs + newLog)
                }
            }
        }
    }

    override fun onCleared() {
        daemonRepository.stop()
        super.onCleared()
    }
}