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

// [分页加载] 扩展UI状态以支持分页
data class LogsUiState(
    val isLoading: Boolean = true,
    val logs: List<UiLogEntry> = emptyList(),
    val isLoadingMore: Boolean = false,
    val hasReachedEnd: Boolean = false
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
            startPollingForNewLogs()
        }
    }

    private fun loadInitialLogs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, logs = emptyList(), hasReachedEnd = false) }
            val history = daemonRepository.getLogs(limit = PAGE_SIZE) ?: emptyList()
            val uiHistory = history.map { log -> mapToUiLog(log) }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    logs = uiHistory,
                    hasReachedEnd = history.size < PAGE_SIZE
                )
            }
        }
    }

    // [分页加载] 新增加载更多日志的函数
    fun loadMoreLogs() {
        // 防止重复加载
        if (_uiState.value.isLoadingMore || _uiState.value.hasReachedEnd) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }

            val lastLogTimestamp = _uiState.value.logs.lastOrNull()?.originalLog?.timestamp
            if (lastLogTimestamp == null) {
                _uiState.update { it.copy(isLoadingMore = false, hasReachedEnd = true) }
                return@launch
            }

            val olderLogs = daemonRepository.getLogs(before = lastLogTimestamp, limit = PAGE_SIZE) ?: emptyList()
            if (olderLogs.isNotEmpty()) {
                val newUiLogs = olderLogs.map { mapToUiLog(it) }
                _uiState.update { currentState ->
                    currentState.copy(
                        isLoadingMore = false,
                        logs = currentState.logs + newUiLogs
                    )
                }
            }

            // 如果返回的日志数量小于请求的数量，说明已经到底了
            if (olderLogs.size < PAGE_SIZE) {
                _uiState.update { it.copy(hasReachedEnd = true) }
            }
            _uiState.update { it.copy(isLoadingMore = false) }
        }
    }

    private fun startPollingForNewLogs() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(3000)
                try {
                    val latestTimestamp = _uiState.value.logs.firstOrNull()?.originalLog?.timestamp
                    val newLogs = daemonRepository.getLogs(since = latestTimestamp)

                    if (!newLogs.isNullOrEmpty()) {
                        val newUiLogs = newLogs.map { mapToUiLog(it) }
                        _uiState.update { currentState ->
                            val currentLogsMap = currentState.logs.associateBy {
                                it.originalLog.timestamp.toString() + it.originalLog.message + it.originalLog.packageName
                            }
                            val newLogsMap = newUiLogs.associateBy {
                                it.originalLog.timestamp.toString() + it.originalLog.message + it.originalLog.packageName
                            }

                            val combinedLogs = (newLogsMap + currentLogsMap).values.toList() // 新日志放前面
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