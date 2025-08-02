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
    val logs: List<UiLogEntry> = emptyList(),
    val isLoadingMore: Boolean = false,
    val hasReachedEnd: Boolean = false,
    val logFiles: List<String> = emptyList(),
    val currentFileIndex: Int = 0
)

class LogsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val PAGE_SIZE = 50
    }

    private val daemonRepository = DaemonRepository.getInstance()
    private val appInfoRepository = AppInfoRepository.getInstance(application)

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        viewModelScope.launch {
            appInfoRepository.getAllApps(forceRefresh = true)
            loadInitialLogs()
            startPollingForNewLogs() // 启动轮询
        }
    }

    private fun loadInitialLogs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, logs = emptyList(), hasReachedEnd = false) }
            
            val files = daemonRepository.getLogFiles() ?: emptyList()
            if (files.isEmpty()) {
                _uiState.update { it.copy(isLoading = false, hasReachedEnd = true) }
                return@launch
            }
            
            _uiState.update { it.copy(logFiles = files, currentFileIndex = 0) }

            val initialLogs = daemonRepository.getLogs(filename = files[0], limit = PAGE_SIZE) ?: emptyList()
            val uiHistory = initialLogs.map { log -> mapToUiLog(log) }
            
            _uiState.update {
                it.copy(
                    isLoading = false,
                    logs = uiHistory,
                    hasReachedEnd = initialLogs.size < PAGE_SIZE && files.size <= 1
                )
            }
        }
    }

    fun loadMoreLogs() {
        val currentState = _uiState.value
        if (currentState.isLoadingMore || currentState.hasReachedEnd) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }

            val lastLogTimestamp = currentState.logs.lastOrNull()?.originalLog?.timestamp
            val currentFilename = currentState.logFiles.getOrNull(currentState.currentFileIndex)

            if (currentFilename == null || lastLogTimestamp == null) {
                _uiState.update { it.copy(isLoadingMore = false, hasReachedEnd = true) }
                return@launch
            }
            
            var olderLogs = daemonRepository.getLogs(
                filename = currentFilename, 
                before = lastLogTimestamp, 
                limit = PAGE_SIZE
            ) ?: emptyList()

            if (olderLogs.isEmpty() && currentState.currentFileIndex + 1 < currentState.logFiles.size) {
                val nextFileIndex = currentState.currentFileIndex + 1
                val nextFilename = currentState.logFiles[nextFileIndex]
                _uiState.update { it.copy(currentFileIndex = nextFileIndex) }
                
                olderLogs = daemonRepository.getLogs(filename = nextFilename, limit = PAGE_SIZE) ?: emptyList()
            }
            
            if (olderLogs.isNotEmpty()) {
                val newUiLogs = olderLogs.map { mapToUiLog(it) }
                _uiState.update { state ->
                    state.copy(
                        isLoadingMore = false,
                        logs = state.logs + newUiLogs
                    )
                }
            }
            
            val isLastFile = currentState.currentFileIndex >= currentState.logFiles.size - 1
            if (olderLogs.size < PAGE_SIZE && isLastFile) {
                _uiState.update { it.copy(hasReachedEnd = true) }
            }
            _uiState.update { it.copy(isLoadingMore = false) }
        }
    }

    // [新] 实现了轮询逻辑
    private fun startPollingForNewLogs() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(3000) 
                try {
                    val latestTimestamp = _uiState.value.logs.firstOrNull()?.originalLog?.timestamp
                    val files = daemonRepository.getLogFiles() ?: emptyList()
                    
                    if (files.isNotEmpty()) {
                        // 如果文件列表变化了，或者我们还没有日志，就重新加载
                        if (files != _uiState.value.logFiles || _uiState.value.logs.isEmpty()) {
                             _uiState.update { it.copy(logFiles = files, currentFileIndex = 0) }
                             // 简单起见，可以只拉最新的
                             if (_uiState.value.logs.isEmpty()) {
                                 loadInitialLogs()
                                 continue
                             }
                        }

                        // 只查询最新文件的增量日志
                        val newLogs = daemonRepository.getLogs(
                            filename = files[0],
                            since = latestTimestamp
                        )

                        if (!newLogs.isNullOrEmpty()) {
                            val newUiLogs = newLogs.map { mapToUiLog(it) }
                            _uiState.update { currentState ->
                                // Prepend new logs to the list
                                currentState.copy(logs = newUiLogs + currentState.logs)
                            }
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
            val baseName = appInfoRepository.getAppInfo(pkg)?.appName
            if (log.userId != 0 && log.userId != -1) {
                "$baseName (分身)"
            } else {
                baseName
            }
        }
        return UiLogEntry(originalLog = log, appName = appName ?: log.packageName)
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}