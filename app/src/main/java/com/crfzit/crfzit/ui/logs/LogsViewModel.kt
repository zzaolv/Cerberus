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
    // [日志重构] 日志列表现在是不可变的，并且总是降序
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
            // 预加载应用信息缓存，对解析日志中的包名有好处
            appInfoRepository.getAllApps(forceRefresh = true)
            // [日志重构] 先加载初始日志
            loadInitialLogs()
            // [日志重构] 然后启动轮询
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
        // 防止重复启动
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(5000) // 每5秒轮询一次
                try {
                    // 获取当前UI上最新的日志的时间戳
                    val latestTimestamp = _uiState.value.logs.firstOrNull()?.originalLog?.timestamp_ms
                    
                    val newLogs = daemonRepository.getLogs(since = latestTimestamp)
                    
                    if (!newLogs.isNullOrEmpty()) {
                        val newUiLogs = newLogs.map { mapToUiLog(it) }
                        
                        _uiState.update { currentState ->
                            // 将新日志（已经是降序）与旧日志合并，然后去重
                            val combinedLogs = (newUiLogs + currentState.logs)
                                .distinctBy { it.originalLog.timestamp_ms.toString() + it.originalLog.message }
                                .sortedByDescending { it.originalLog.timestamp_ms }

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
        // [日志重构] ViewModel销毁时停止轮询
        pollingJob?.cancel()
        super.onCleared()
    }
}