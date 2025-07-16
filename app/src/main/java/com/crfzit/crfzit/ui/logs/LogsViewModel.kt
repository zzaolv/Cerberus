// app/src/main/java/com/crfzit/crfzit/ui/logs/LogsViewModel.kt
package com.crfzit.crfzit.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class LogsViewModel : ViewModel() {

    private val udsClient = UdsClient(viewModelScope)
    private val gson = Gson()
    private var currentPage = 0
    private val logsPerPage = 50

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    init {
        udsClient.start()
        observeLogResponses()
        loadMoreLogs()
    }

    private fun observeLogResponses() {
        viewModelScope.launch {
            udsClient.incomingMessages.collect { jsonLine ->
                try {
                    val msg = gson.fromJson(jsonLine, Map::class.java)
                    if (msg["type"] == "resp.logs") {
                        val payload = msg["payload"]
                        val logListType = object : TypeToken<List<Map<String, Any>>>() {}.type
                        val rawLogs: List<Map<String, Any>> = gson.fromJson(gson.toJson(payload), logListType)

                        val newLogs = rawLogs.map {
                            LogEntry(
                                timestamp = (it["timestamp"] as Double).toLong(),
                                level = LogLevel.entries.getOrElse((it["level"] as Double).toInt()) { LogLevel.INFO },
                                message = it["message"] as String,
                                appName = it["app_name"] as? String
                            )
                        }

                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                logs = it.logs + newLogs,
                                canLoadMore = newLogs.size == logsPerPage
                            )
                        }
                    }
                } catch (e: Exception) {
                    // ignore parse errors
                }
            }
        }
    }

    fun loadMoreLogs() {
        if (!_uiState.value.isLoading) {
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
    }

    override fun onCleared() {
        super.onCleared()
        udsClient.stop()
    }
}