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

    init {
        viewModelScope.launch {
            // 预加载应用信息
            appInfoRepository.getAllApps(forceRefresh = true)
            // 加载初始日志
            loadInitialLogs()
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
                    // 如果第一页就没满，并且只有一个文件，那才算到底
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

            // 如果当前文件没找到更多日志，并且还有更旧的文件，就去读下一个文件
            if (olderLogs.isEmpty() && currentState.currentFileIndex + 1 < currentState.logFiles.size) {
                val nextFileIndex = currentState.currentFileIndex + 1
                val nextFilename = currentState.logFiles[nextFileIndex]
                _uiState.update { it.copy(currentFileIndex = nextFileIndex) }
                
                // 从新文件的末尾开始读
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
            
            // 如果这次返回的日志数量少于请求数，并且已经是最后一个文件了，说明真的到底了
            val isLastFile = currentState.currentFileIndex >= currentState.logFiles.size - 1
            if (olderLogs.size < PAGE_SIZE && isLastFile) {
                _uiState.update { it.copy(hasReachedEnd = true) }
            }
            _uiState.update { it.copy(isLoadingMore = false) }
        }
    }
    
    // Polling is complex with file-based logs, we can disable it for now or implement it later
    // private fun startPollingForNewLogs() { ... }

    private suspend fun mapToUiLog(log: LogEntry): UiLogEntry {
        val appName = log.packageName?.let { pkg ->
            // 对分身应用添加后缀
            val baseName = appInfoRepository.getAppInfo(pkg)?.appName
            if (log.user_id != 0 && log.user_id != -1) {
                "$baseName (分身)"
            } else {
                baseName
            }
        }
        return UiLogEntry(originalLog = log, appName = appName ?: log.packageName)
    }

    override fun onCleared() {
        super.onCleared()
    }
}