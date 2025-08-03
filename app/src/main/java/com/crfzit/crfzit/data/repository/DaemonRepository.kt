// app/src/main/java/com/crfzit/crfzit/data/repository/DaemonRepository.kt
package com.crfzit.crfzit.data.repository

import android.util.Log
import com.crfzit.crfzit.data.model.*
import com.crfzit.crfzit.data.uds.TcpClient
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class GetLogFilesPayload(val placeholder: Int = 0)
data class GetLogsByFilePayload(
    val filename: String?,
    val before: Long?,
    val since: Long?,
    val limit: Int?
)

class DaemonRepository private constructor(
    private val scope: CoroutineScope
) {
    private val tcpClient = TcpClient(scope)
    private val gson = Gson()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()

    init {
        tcpClient.start()
        scope.launch(Dispatchers.IO) {
            tcpClient.incomingMessages.collect { jsonLine ->
                try {
                    val baseMsg = gson.fromJson(jsonLine, BaseMessage::class.java)
                    if ((baseMsg.type.startsWith("resp.") || baseMsg.type.startsWith("error.")) && baseMsg.requestId != null) {
                        pendingRequests.remove(baseMsg.requestId)?.complete(jsonLine)
                    }
                } catch (e: JsonSyntaxException) {
                    // Ignore, not a response message
                }
            }
        }
    }

    private fun mapPayloadToMetricsRecord(p: MetricsRecordPayload): MetricsRecord {
        return MetricsRecord(
            timestamp = p.timestamp,
            totalCpuUsagePercent = p.totalCpuUsagePercent, // [核心修改]
            perCoreCpuUsagePercent = p.perCoreCpuUsagePercent ?: emptyList(), // [核心新增]
            memTotalKb = p.memTotalKb,
            memAvailableKb = p.memAvailableKb,
            swapTotalKb = p.swapTotalKb,
            swapFreeKb = p.swapFreeKb,
            batteryLevel = p.batteryLevel,
            batteryTempCelsius = p.batteryTempCelsius,
            batteryPowerWatt = p.batteryPowerWatt,
            isCharging = p.isCharging,
            isScreenOn = p.isScreenOn,
            isAudioPlaying = p.isAudioPlaying,
            isLocationActive = p.isLocationActive
        )
    }

    fun getDashboardStream(): Flow<DashboardPayload> = tcpClient.incomingMessages
        .mapNotNull { jsonLine ->
            try {
                val type = object : TypeToken<CerberusMessage<DashboardPayload>>() {}.type
                val msg = gson.fromJson<CerberusMessage<DashboardPayload>>(jsonLine, type)
                if (msg?.type == "stream.dashboard_update") msg.payload else null
            } catch (e: Exception) {
                Log.w("DaemonRepository", "Failed to parse DashboardPayload: ${e.message}")
                null
            }
        }

    suspend fun getLogFiles(): List<String>? {
        return query("query.get_log_files", GetLogFilesPayload()) { responseJson ->
            val responseType = object : TypeToken<CerberusMessage<List<String>>>() {}.type
            val message = gson.fromJson<CerberusMessage<List<String>>>(responseJson, responseType)
            if (message?.type == "resp.get_log_files") {
                message.payload
            } else {
                null
            }
        }
    }

    suspend fun getLogs(
        filename: String,
        before: Long? = null,
        since: Long? = null,
        limit: Int? = null
    ): List<LogEntry>? {
        val payload = GetLogsByFilePayload(filename = filename, before = before, since = since, limit = limit)
        return query("query.get_logs", payload) { responseJson ->
            val responseType = object : TypeToken<CerberusMessage<List<LogEntryPayload>>>() {}.type
            val message = gson.fromJson<CerberusMessage<List<LogEntryPayload>>>(responseJson, responseType)
            if (message?.type == "resp.get_logs") {
                message.payload.map { p ->
                    LogEntry(p.timestamp, LogLevel.fromInt(p.level), p.category, p.message, p.packageName, p.userId ?: -1)
                }
            } else {
                Log.e("DaemonRepository", "Query 'get_logs' received unexpected response type '${message?.type}'")
                null
            }
        }
    }

    fun getStatsStream(): Flow<MetricsRecord> = tcpClient.incomingMessages
        .mapNotNull { jsonLine ->
            try {
                val type = object : TypeToken<CerberusMessage<MetricsRecordPayload>>() {}.type
                val msg = gson.fromJson<CerberusMessage<MetricsRecordPayload>>(jsonLine, type)
                if (msg?.type == "stream.new_stats_record") {
                    msg.payload?.let { mapPayloadToMetricsRecord(it) } // [核心修改]
                } else null
            } catch (e: Exception) {
                Log.w("DaemonRepository", "Failed to parse MetricsRecordPayload: ${e.message}")
                null
            }
        }

    suspend fun getHistoryStats(): List<MetricsRecord>? = query("query.get_history_stats", EmptyPayload) { responseJson ->
        val type = object : TypeToken<CerberusMessage<List<MetricsRecordPayload>>>() {}.type
        val message = gson.fromJson<CerberusMessage<List<MetricsRecordPayload>>>(responseJson, type)
        if (message?.type == "resp.history_stats") {
            message.payload.map { mapPayloadToMetricsRecord(it) } // [核心修改]
        } else {
            null
        }
    }

    private suspend fun <ReqT, RespT> query(
        queryType: String,
        payload: ReqT,
        responseParser: (String) -> RespT?
    ): RespT? {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingRequests[reqId] = deferred
        val requestMsg = CerberusMessage(type = queryType, requestId = reqId, payload = payload)
        tcpClient.sendMessage(gson.toJson(requestMsg))
        return try {
            withTimeout(5000) { deferred.await() }.let(responseParser)
        } catch (e: Exception) {
            Log.e("DaemonRepository", "Failed to query '$queryType': ${e.message}")
            pendingRequests.remove(reqId)
            null
        }
    }

    fun setPolicy(config: FullConfigPayload) {
        val message = CerberusMessage(type = "cmd.set_policy", payload = config)
        tcpClient.sendMessage(gson.toJson(message))
    }

    suspend fun getAllPolicies(): FullConfigPayload? {
        return query("query.get_all_policies", EmptyPayload) { responseJson ->
            val type = object : TypeToken<CerberusMessage<FullConfigPayload>>() {}.type
            val message = gson.fromJson<CerberusMessage<FullConfigPayload>>(responseJson, type)
            if (message?.type == "resp.all_policies") message.payload else null
        }
    }

    fun requestDashboardRefresh() {
        val message = CerberusMessage(type = "query.refresh_dashboard", payload = EmptyPayload)
        tcpClient.sendMessage(gson.toJson(message))
    }

    fun stop() {
        tcpClient.stop()
        pendingRequests.values.forEach { it.cancel("Repository is stopping.") }
        pendingRequests.clear()
    }

    fun setMasterConfig(payload: Map<String, Any>) {
        val message = CerberusMessage(type = "cmd.set_master_config", payload = payload)
        tcpClient.sendMessage(gson.toJson(message))
    }

    private data class BaseMessage(val type: String, @SerializedName("req_id") val requestId: String?)
    private object EmptyPayload

    companion object {
        @Volatile private var INSTANCE: DaemonRepository? = null
        fun getInstance(scope: CoroutineScope? = null): DaemonRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: scope?.let {
                    DaemonRepository(it).also { INSTANCE = it }
                } ?: throw IllegalStateException("CoroutineScope must be provided for the first initialization")
            }
        }
    }
}