// app/src/main/java/com/crfzit/crfzit/ui/logs/LogsViewModel.kt
package com.crfzit.crfzit.ui.logs

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.LogEntry
import com.crfzit.crfzit.data.model.LogLevel
import com.crfzit.crfzit.data.repository.AppInfoRepository
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

// 数据模型保持不变
sealed interface TimelineItem {
    val id: String
    val timestamp: Long
}

data class SingleLogItem(val log: UiLogEntry) : TimelineItem {
    override val id: String = "${log.originalLog.timestamp}-${log.originalLog.level}-${log.originalLog.message}-${UUID.randomUUID()}"
    override val timestamp: Long = log.originalLog.timestamp
}

data class LogGroupItem(val parentLog: UiLogEntry, val childLogs: List<UiLogEntry>) : TimelineItem {
    override val id: String = "${parentLog.originalLog.timestamp}-group-${UUID.randomUUID()}"
    override val timestamp: Long = parentLog.originalLog.timestamp
}

data class UiLogEntry(val originalLog: LogEntry, val appName: String?)

// [核心修复] 1. State持有原始日志数据，UI模型由它派生，确保数据源统一
data class LogsUiState(
    val isLoading: Boolean = true,
    val rawLogs: List<UiLogEntry> = emptyList(), // 保存原始、未分组的日志
    val timelineItems: List<TimelineItem> = emptyList(), // 保存分组后的UI模型
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
    private val uiStateUpdateMutex = Mutex()

    init {
        viewModelScope.launch {
            appInfoRepository.getAllApps(forceRefresh = true)
            loadInitialLogs()
            startPollingForNewLogs()
        }
    }

    private fun loadInitialLogs() {
        viewModelScope.launch {
            uiStateUpdateMutex.withLock {
                _uiState.value = LogsUiState(isLoading = true)
            }

            val files = daemonRepository.getLogFiles() ?: emptyList()
            if (files.isEmpty()) {
                _uiState.update { it.copy(isLoading = false, hasReachedEnd = true) }
                return@launch
            }

            val initialLogs = daemonRepository.getLogs(filename = files[0], limit = PAGE_SIZE) ?: emptyList()
            val uiHistory = initialLogs.map { mapToUiLog(it) }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    rawLogs = uiHistory,
                    timelineItems = processLogsIntoTimelineItems(uiHistory),
                    logFiles = files,
                    currentFileIndex = 0,
                    hasReachedEnd = initialLogs.size < PAGE_SIZE && files.size <= 1
                )
            }
        }
    }

    fun loadMoreLogs() {
        viewModelScope.launch {
            if (_uiState.value.isLoadingMore || _uiState.value.hasReachedEnd) return@launch

            uiStateUpdateMutex.withLock {
                val currentState = _uiState.value
                if (currentState.isLoadingMore || currentState.hasReachedEnd) return@withLock
                _uiState.update { it.copy(isLoadingMore = true) }

                val lastLogTimestamp = currentState.rawLogs.lastOrNull()?.originalLog?.timestamp
                val currentFilename = currentState.logFiles.getOrNull(currentState.currentFileIndex)

                if (currentFilename == null || lastLogTimestamp == null) {
                    _uiState.update { it.copy(isLoadingMore = false, hasReachedEnd = true) }
                    return@withLock
                }

                var olderLogs = daemonRepository.getLogs(filename = currentFilename, before = lastLogTimestamp, limit = PAGE_SIZE) ?: emptyList()
                var nextFileIndex = currentState.currentFileIndex
                if (olderLogs.isEmpty() && currentState.currentFileIndex + 1 < currentState.logFiles.size) {
                    nextFileIndex = currentState.currentFileIndex + 1
                    olderLogs = daemonRepository.getLogs(filename = currentState.logFiles[nextFileIndex], limit = PAGE_SIZE) ?: emptyList()
                }

                val newUiLogs = if (olderLogs.isNotEmpty()) olderLogs.map { mapToUiLog(it) } else emptyList()
                val combinedRawLogs = currentState.rawLogs + newUiLogs
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        rawLogs = combinedRawLogs,
                        timelineItems = processLogsIntoTimelineItems(combinedRawLogs),
                        currentFileIndex = nextFileIndex,
                        hasReachedEnd = olderLogs.size < PAGE_SIZE && nextFileIndex >= it.logFiles.size - 1
                    )
                }
            }
        }
    }

    private fun startPollingForNewLogs() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(5000) // 增加轮询间隔以减少不必要的刷新
                try {
                    val latestTimestamp = _uiState.value.rawLogs.firstOrNull()?.originalLog?.timestamp
                    val files = daemonRepository.getLogFiles() ?: emptyList()
                    if (files.isEmpty()) continue

                    val newLogs = daemonRepository.getLogs(filename = files[0], since = latestTimestamp)
                    if (!newLogs.isNullOrEmpty()) {
                        val newUiLogs = newLogs.map { mapToUiLog(it) }
                        uiStateUpdateMutex.withLock {
                            // [核心修复] 2. 合并新旧原始日志，去重，然后整体重新生成UI模型
                            val currentState = _uiState.value
                            val combinedRawLogs = (newUiLogs + currentState.rawLogs)
                                .distinctBy { log -> "${log.originalLog.timestamp}-${log.originalLog.message}-${log.originalLog.level}" }
                                .sortedByDescending { log -> log.originalLog.timestamp }

                            _uiState.update {
                                it.copy(
                                    rawLogs = combinedRawLogs,
                                    timelineItems = processLogsIntoTimelineItems(combinedRawLogs),
                                    logFiles = if (files != currentState.logFiles) files else currentState.logFiles
                                )
                            }
                        }
                    } else if (files != _uiState.value.logFiles) {
                        _uiState.update { it.copy(logFiles = files) }
                    }
                } catch (e: Exception) {
                    Log.w("LogsViewModel", "Error polling for new logs: ${e.message}")
                }
            }
        }
    }

    private fun processLogsIntoTimelineItems(logs: List<UiLogEntry>): List<TimelineItem> {
        if (logs.isEmpty()) return emptyList()
        val result = mutableListOf<TimelineItem>()
        val sortedLogs = logs.sortedBy { it.originalLog.timestamp }
        var i = 0
        while (i < sortedLogs.size) {
            val currentLog = sortedLogs[i]
            if (currentLog.originalLog.level == LogLevel.BATCH_PARENT) {
                val children = mutableListOf<UiLogEntry>()
                var j = i + 1
                while (j < sortedLogs.size &&
                    sortedLogs[j].originalLog.level == LogLevel.REPORT &&
                    sortedLogs[j].originalLog.timestamp == currentLog.originalLog.timestamp
                ) {
                    children.add(sortedLogs[j])
                    j++
                }
                result.add(LogGroupItem(currentLog, children))
                i = if (children.isNotEmpty()) j else i + 1
            } else {
                result.add(SingleLogItem(currentLog))
                i++
            }
        }
        return result.asReversed()
    }

    private suspend fun mapToUiLog(log: LogEntry): UiLogEntry {
        val appName = log.packageName?.let { pkg ->
            val baseName = appInfoRepository.getAppInfo(pkg)?.appName
            if (log.userId != 0 && log.userId != -1) "$baseName (分身)" else baseName
        }
        if (log.level == LogLevel.REPORT) {
            val originalMessageTitle = log.message.substringBefore(" 总计:")
            return UiLogEntry(originalLog = log, appName = appName ?: originalMessageTitle)
        }
        return UiLogEntry(originalLog = log, appName = appName ?: log.packageName)
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}