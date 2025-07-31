// app/src/main/java/com/crfzit/crfzit/ui/logs/LogsViewModel.kt
package com.crfzit.crfzit.ui.logs

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.LogEntry
import com.crfzit.crfzit.data.repository.AppInfoRepository
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private val daemonRepository = DaemonRepository.getInstance()
    private val appInfoRepository = AppInfoRepository.getInstance(application)

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        viewModelScope.launch {
            appInfoRepository.getAllApps(forceRefresh = true)
            loadInitialLogs()
            startPollingForNewLogs()
        }
    }

    private fun loadInitialLogs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val history = daemonRepository.getLogs(since = null) ?: emptyList()
            val uiHistory = history.map { log -> mapToUiLog(log) }
            _uiState.update { it.copy(isLoading = false, logs = uiHistory) }
        }
    }

    private fun startPollingForNewLogs() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(5000)
                try {
                    // [修正] 使用 .timestamp 替换 .timestamp_ms
                    val latestTimestamp = _uiState.value.logs.firstOrNull()?.originalLog?.timestamp

                    val newLogs = daemonRepository.getLogs(since = latestTimestamp)

                    if (!newLogs.isNullOrEmpty()) {
                        val newUiLogs = newLogs.map { mapToUiLog(it) }

                        _uiState.update { currentState ->
                            // [修正] 使用 .timestamp 替换 .timestamp_ms
                            val combinedLogs = (newUiLogs + currentState.logs)
                                .distinctBy { it.originalLog.timestamp.toString() + it.originalLog.message }
                                .sortedByDescending { it.originalLog.timestamp }

                            currentState.copy(logs = combinedLogs)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("LogsViewModel", "Error polling for new logs: ${e.message}")
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
        pollingJob?.cancel()
        super.onCleared()
    }
}