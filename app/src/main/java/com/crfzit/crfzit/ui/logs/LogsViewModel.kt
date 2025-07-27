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

// 用于UI显示的日志数据类
data class UiLogEntry(
    val originalLog: LogEntry,
    val appName: String? // 可以为null，如果查找失败
)

data class LogsUiState(
    val isLoading: Boolean = true,
    val logs: List<UiLogEntry> = emptyList() 
)

class LogsViewModel(application: Application) : AndroidViewModel(application) {

    private val daemonRepository = DaemonRepository(viewModelScope)
    private val appInfoRepository = AppInfoRepository.getInstance(application)
    
    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    init {
        // 预热应用信息缓存，对日志显示体验至关重要
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
            // 批量转换历史日志
            val uiHistory = history.map { log -> mapToUiLog(log) }
            _uiState.update { it.copy(isLoading = false, logs = uiHistory) }
        }
    }

    private fun listenForNewLogs() {
        viewModelScope.launch {
            daemonRepository.getLogStream().collect { newLog ->
                // 转换新收到的单条日志
                val newUiLog = mapToUiLog(newLog)
                _uiState.update { currentState ->
                    // 保持日志列表从旧到新排序
                    currentState.copy(logs = currentState.logs + newUiLog)
                }
            }
        }
    }

    // 核心转换函数：将后端LogEntry转换为包含应用名的UiLogEntry
    private suspend fun mapToUiLog(log: LogEntry): UiLogEntry {
        // 如果日志条目包含包名，就去仓库查找对应的应用名
        val appName = log.packageName?.let { pkg ->
            appInfoRepository.getAppInfo(pkg)?.appName
        }
        return UiLogEntry(originalLog = log, appName = appName)
    }

    override fun onCleared() {
        daemonRepository.stop()
        super.onCleared()
    }
}