// app/src/main/java/com/crfzit/crfzit/ui/logs/LogsViewModel.kt
package com.crfzit.crfzit.ui.logs

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.CerberusApplication
import com.crfzit.crfzit.data.model.LogEntry
import com.crfzit.crfzit.data.model.LogEventType
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
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
        requestLogsPage(0)
    }

    private fun observeLogResponses() {
        viewModelScope.launch {
            udsClient.incomingMessages
                .filter { it.contains("\"type\":\"resp.logs\"") }
                .collect { jsonLine ->
                    try {
                        val msgType = object : TypeToken<Map<String, Any>>() {}.type
                        val msg: Map<String, Any> = gson.fromJson(jsonLine, msgType)

                        val payloadJson = gson.toJson(msg["payload"])
                        val logListType = object : TypeToken<List<Map<String, Any>>>() {}.type
                        val rawLogs: List<Map<String, Any>> = gson.fromJson(payloadJson, logListType)

                        val newLogs = rawLogs.mapNotNull {
                            try {
                                val timestampNum = it["timestamp"] as? Number
                                val eventTypeNum = it["event_type"] as? Number
                                val payloadMap = it["payload"] as? Map<String, Any>

                                if (timestampNum == null || eventTypeNum == null || payloadMap == null) {
                                    Log.w("LogsViewModel", "Skipping log entry with null values: $it")
                                    return@mapNotNull null
                                }

                                LogEntry(
                                    timestamp = timestampNum.toLong(),
                                    eventType = LogEventType.entries.getOrElse(eventTypeNum.toInt()) { LogEventType.UNKNOWN },
                                    payload = payloadMap
                                )
                            } catch (e: Exception) {
                                Log.w("LogsViewModel", "Skipping malformed log entry: $it", e)
                                null
                            }
                        }

                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                logs = (it.logs + newLogs).distinctBy { log -> log.timestamp.toString() + log.payload.toString() },
                                canLoadMore = newLogs.size >= logsPerPage
                            )
                        }
                        
                        if (newLogs.isNotEmpty()) {
                            currentPage++
                        }

                    } catch (e: JsonSyntaxException) {
                        Log.e("LogsViewModel", "JSON parse error for logs response", e)
                         _uiState.update { it.copy(isLoading = false) }
                    } catch (e: Exception) {
                        Log.e("LogsViewModel", "Error processing logs response", e)
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
        }
    }

    fun loadMoreLogs() {
        if (uiState.value.isLoading || !uiState.value.canLoadMore) {
            return
        }
        _uiState.update { it.copy(isLoading = true) }
        requestLogsPage(currentPage)
    }
    
    private fun requestLogsPage(page: Int) {
        val requestId = "logs-${UUID.randomUUID()}"
        val requestPayload = mapOf(
            "limit" to logsPerPage,
            "offset" to page * logsPerPage
        )
        val request = mapOf(
            "v" to 1,
            "type" to "query.get_logs",
            "req_id" to requestId,
            "payload" to requestPayload
        )
        udsClient.sendMessage(gson.toJson(request))
        Log.i("LogsViewModel", "Requested logs page $page")
    }
}