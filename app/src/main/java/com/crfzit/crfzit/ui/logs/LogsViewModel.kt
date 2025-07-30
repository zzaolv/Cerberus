// app/src/main/java/com/crfzit/crfzit/ui/logs/LogsViewModel.kt
package com.crfzit.crfzit.ui.logs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.LogEntry
import com.crfzit.crfzit.data.repository.AppInfoRepository
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiLogEntry(
    val originalLog: LogEntry,
    val appName: String?
)

data class LogsUiState(
    val isLoading: Boolean = true,
    val logs: List<UiLogEntry> = emptyList() 
)

class LogsViewModel(application: Application) : AndroidViewModel(application) {

    // 架构重构：获取唯一的单例实例
    private val daemonRepository = DaemonRepository.getInstance()
    private val appInfoRepository = AppInfoRepository.getInstance(application)
    
    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appInfoRepository.getAllApps(forceRefresh = true)
        }
        loadInitialLogs()
        listenForNewLogs()
    }
    
    private fun loadInitialLogs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val history = daemonRepository.getAllLogs() ?: emptyList()
            val uiHistory = history.map { log -> mapToUiLog(log) }
            _uiState.update { it.copy(isLoading = false, logs = uiHistory) }
        }
    }

    private fun listenForNewLogs() {
        viewModelScope.launch {
            daemonRepository.getLogStream().collect { newLog ->
                val newUiLog = mapToUiLog(newLog)
                _uiState.update { currentState ->
                    currentState.copy(logs = currentState.logs + newUiLog)
                }
            }
        }
    }

    private suspend fun mapToUiLog(log: LogEntry): UiLogEntry {
        val appName = log.packageName?.let { pkg ->
            appInfoRepository.getAppInfo(pkg)?.appName
        }
        return UiLogEntry(originalLog = log, appName = appName)
    }

    override fun onCleared() {
        super.onCleared()
        // 架构重构：ViewModel不再负责停止Repository
    }
}