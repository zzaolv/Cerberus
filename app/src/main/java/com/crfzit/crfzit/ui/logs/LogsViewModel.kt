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

// [新增] 用于UI显示的日志数据类
data class UiLogEntry(
    val originalLog: LogEntry,
    val appName: String?
)

data class LogsUiState(
    val isLoading: Boolean = true,
    val logs: List<UiLogEntry> = emptyList() // [修改] 使用新的UI模型
)

class LogsViewModel(application: Application) : AndroidViewModel(application) {

    private val daemonRepository = DaemonRepository(viewModelScope)
    // [新增] 注入AppInfoRepository
    private val appInfoRepository = AppInfoRepository.getInstance(application)
    
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
            // [修改] 批量转换
            val uiHistory = history.map { log -> mapToUiLog(log) }
            _uiState.update { it.copy(isLoading = false, logs = uiHistory) }
        }
    }

    private fun listenForNewLogs() {
        viewModelScope.launch {
            daemonRepository.getLogStream().collect { newLog ->
                // [修改] 单条转换
                val newUiLog = mapToUiLog(newLog)
                _uiState.update { currentState ->
                    currentState.copy(logs = currentState.logs + newUiLog)
                }
            }
        }
    }

    // [新增] 转换函数
    private suspend fun mapToUiLog(log: LogEntry): UiLogEntry {
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