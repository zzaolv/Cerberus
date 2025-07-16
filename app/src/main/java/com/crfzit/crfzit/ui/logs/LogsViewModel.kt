// app/src/main/java/com/crfzit/crfzit/ui/logs/LogsViewModel.kt
package com.crfzit.crfzit.ui.logs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.CerberusApplication
import com.crfzit.crfzit.data.model.LogEntry
import com.crfzit.crfzit.data.model.LogLevel
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

data class LogsUiState(
    val isLoading: Boolean = true,
    val logs: List<LogEntry> = emptyList(),
    val canLoadMore: Boolean = true
)

class LogsViewModel(application: Application) : AndroidViewModel(application) {

    private val appScope = (application as CerberusApplication).applicationScope
    private val udsClient = UdsClient.getInstance(appScope)

    private val gson = Gson()
    private var currentPage = 0
    private val logsPerPage = 50

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    init {
        observeLogResponses()
        loadMoreLogs()
    }

    private fun observeLogResponses() {
        viewModelScope.launch {
            udsClient.incomingMessages.filter { it.contains("resp.logs") }.collect { jsonLine ->
                try {
                    val msgType = object : TypeToken<Map<String, Any>>() {}.type
                    val msg: Map<String, Any> = gson.fromJson(jsonLine, msgType)

                    val payloadJson = gson.toJson(msg["payload"])
                    val logListType = object : TypeToken<List<Map<String, Any>>>() {}.type
                    val rawLogs: List<Map<String, Any>> = gson.fromJson(payloadJson, logListType)

                    val newLogs = rawLogs.mapNotNull {
                        try {
                            LogEntry(
                                timestamp = (it["timestamp"] as Double).toLong(),
                                level = LogLevel.entries.getOrElse((it["level"] as Double).toInt()) { LogLevel.INFO },
                                message = it["message"] as String,
                                appName = it["app_name"] as? String
                            )
                        } catch (e: Exception) {
                            null // Skip malformed log entries
                        }
                    }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            logs = it.logs + newLogs,
                            canLoadMore = newLogs.size >= logsPerPage
                        )
                    }
                } catch (e: Exception) {
                    // ignore parse errors
                }
            }
        }
    }

    fun loadMoreLogs() {
        if (uiState.value.isLoading || !uiState.value.canLoadMore) return

        _uiState.update { it.copy(isLoading = true) }
        val requestId = "logs-${UUID.randomUUID()}"
        val requestPayload = mapOf(
            "limit" to logsPerPage,
            "offset" to currentPage * logsPerPage
        )
        val request = mapOf(
            "v" to 1,
            "type" to "query.get_logs",
            "req_id" to requestId,
            "payload" to requestPayload
        )
        udsClient.sendMessage(gson.toJson(request))
        currentPage++
    }

    // 【核心修复】移除 onCleared 方法
    // override fun onCleared() {
    //     super.onCleared()
    //     udsClient.stop() // <-- This was the error
    // }
}