// app/src/main/java/com/crfzit/crfzit/data/repository/DaemonRepository.kt
package com.crfzit.crfzit.data.repository

import android.util.Log
import com.crfzit.crfzit.data.model.*
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class DaemonRepository(
    private val scope: CoroutineScope
) {
    private val udsClient = UdsClient(scope)
    private val gson = Gson()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()

    init {
        udsClient.start()
        scope.launch(Dispatchers.IO) {
            udsClient.incomingMessages.collect { jsonLine ->
                try {
                    val baseMsg = gson.fromJson(jsonLine, BaseMessage::class.java)
                    if (baseMsg.type.startsWith("resp.") && baseMsg.requestId != null) {
                        pendingRequests.remove(baseMsg.requestId)?.complete(jsonLine)
                    }
                } catch (e: JsonSyntaxException) {
                    // Ignore, not a response message
                }
            }
        }
    }

    fun getDashboardStream(): Flow<DashboardPayload> = udsClient.incomingMessages
        .mapNotNull { jsonLine ->
            try {
                val type = object : TypeToken<CerberusMessage<DashboardPayload>>() {}.type
                val msg = gson.fromJson<CerberusMessage<DashboardPayload>>(jsonLine, type)
                if (msg.type == "stream.dashboard_update") msg.payload else null
            } catch (e: Exception) {
                null
            }
        }

    // [REFACTORED] setPolicy now sends the entire config bundle.
    // A more advanced implementation would send individual config changes.
    fun setPolicy(config: FullConfigPayload) {
        val message = CerberusMessage(type = "cmd.set_policy", payload = config)
        udsClient.sendMessage(gson.toJson(message))
    }

    // [修改] 增加新的流式消息处理
    fun getLogStream(): Flow<LogEntry> = udsClient.incomingMessages
        .mapNotNull { jsonLine ->
            try {
                val type = object : TypeToken<CerberusMessage<LogEntryPayload>>() {}.type
                val msg = gson.fromJson<CerberusMessage<LogEntryPayload>>(jsonLine, type)
                if (msg.type == "stream.new_log_entry") {
                    val p = msg.payload
                    LogEntry(p.timestamp, LogLevel.fromInt(p.level), p.category, p.message, p.packageName, p.userId ?: -1)
                } else null
            } catch (e: Exception) { null }
        }
        
    fun getStatsStream(): Flow<MetricsRecord> = udsClient.incomingMessages
        .mapNotNull { jsonLine ->
             try {
                val type = object : TypeToken<CerberusMessage<MetricsRecordPayload>>() {}.type
                val msg = gson.fromJson<CerberusMessage<MetricsRecordPayload>>(jsonLine, type)
                if (msg.type == "stream.new_stats_record") {
                    val p = msg.payload
                    MetricsRecord(p.timestamp, p.cpuUsagePercent, p.memUsedKb, p.batteryLevel, p.batteryTempCelsius, p.batteryPowerWatt, p.isCharging, p.isScreenOn, p.isAudioPlaying, p.isLocationActive)
                } else null
            } catch (e: Exception) { null }
        }

    // [新增] 获取历史日志
    suspend fun getAllLogs(): List<LogEntry>? = query("query.get_all_logs") { json ->
        val type = object : TypeToken<List<LogEntryPayload>>() {}.type
        val payloads = gson.fromJson<List<LogEntryPayload>>(json, type)
        payloads.map { p ->
            LogEntry(p.timestamp, LogLevel.fromInt(p.level), p.category, p.message, p.packageName, p.userId ?: -1)
        }
    }

    // [新增] 获取历史统计数据
    suspend fun getHistoryStats(): List<MetricsRecord>? = query("query.get_history_stats") { json ->
        val type = object : TypeToken<List<MetricsRecordPayload>>() {}.type
        val payloads = gson.fromJson<List<MetricsRecordPayload>>(json, type)
        payloads.map { p ->
             MetricsRecord(p.timestamp, p.cpuUsagePercent, p.memUsedKb, p.batteryLevel, p.batteryTempCelsius, p.batteryPowerWatt, p.isCharging, p.isScreenOn, p.isAudioPlaying, p.isLocationActive)
        }
    }
    
    // [新增] 通用查询辅助函数
    private suspend fun <T> query(type: String, payloadParser: (String) -> T): T? {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingRequests[reqId] = deferred

        val requestMsg = CerberusMessage(type = type, requestId = reqId, payload = EmptyPayload)
        udsClient.sendMessage(gson.toJson(requestMsg))

        return try {
            val responseJson = withTimeout(5000) { deferred.await() }
            val responseType = when(type) {
                "query.get_all_logs" -> "resp.all_logs"
                "query.get_history_stats" -> "resp.history_stats"
                else -> "resp.unknown"
            }
            
            val baseMsg = gson.fromJson(responseJson, BaseMessageWithPayload::class.java)
            if (baseMsg.type == responseType) {
                payloadParser(baseMsg.payload.toString())
            } else null
        } catch (e: Exception) {
            Log.e("DaemonRepository", "Failed to query '$type': ${e.message}")
            pendingRequests.remove(reqId)
            null
        }
    }

    // [REFACTORED] getAllPolicies now expects the new FullConfigPayload
    suspend fun getAllPolicies(): FullConfigPayload? {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingRequests[reqId] = deferred

        val requestMsg = CerberusMessage(type = "query.get_all_policies", requestId = reqId, payload = EmptyPayload)
        udsClient.sendMessage(gson.toJson(requestMsg))

        return try {
            val responseJson = withTimeout(5000) { deferred.await() }
            val type = object : TypeToken<CerberusMessage<FullConfigPayload>>() {}.type
            val message = gson.fromJson<CerberusMessage<FullConfigPayload>>(responseJson, type)
            if (message.type == "resp.all_policies") message.payload else null
        } catch (e: Exception) {
            Log.e("DaemonRepository", "Failed to get all policies: ${e.message}")
            pendingRequests.remove(reqId)
            null
        }
    }

    fun requestDashboardRefresh() {
        val message = CerberusMessage(type = "query.refresh_dashboard", payload = EmptyPayload)
        udsClient.sendMessage(gson.toJson(message))
    }

    fun stop() {
        udsClient.stop()
        pendingRequests.values.forEach { it.cancel("Repository is stopping.") }
        pendingRequests.clear()
    }

    private data class BaseMessage(val type: String, @SerializedName("req_id") val requestId: String?)
    private object EmptyPayload

    fun setMasterConfig(payload: Map<String, Any>) {
        val message = CerberusMessage(type = "cmd.set_master_config", payload = payload)
        udsClient.sendMessage(gson.toJson(message))
    }

    private data class BaseMessageWithPayload(
        val type: String, 
        @SerializedName("req_id") val requestId: String?,
        val payload: com.google.gson.JsonElement
    )

}