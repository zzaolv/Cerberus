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

    suspend fun getLogs(
        since: Long? = null,
        before: Long? = null,
        limit: Int? = null
    ): List<LogEntry>? {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingRequests[reqId] = deferred

        val requestPayload = GetLogsPayload(since = since, before = before, limit = limit)
        val requestMsg = CerberusMessage(type = "query.get_logs", requestId = reqId, payload = requestPayload)
        tcpClient.sendMessage(gson.toJson(requestMsg))

        return try {
            val responseJson = withTimeout(5000) { deferred.await() }
            val responseType = object : TypeToken<CerberusMessage<List<LogEntryPayload>>>() {}.type
            val message = gson.fromJson<CerberusMessage<List<LogEntryPayload>>>(responseJson, responseType)

            if (message?.type == "resp.get_logs") {
                message.payload.map { p ->
                    // [核心修复] 更新构造函数调用，不再传递 details
                    LogEntry(p.timestamp, LogLevel.fromInt(p.level), p.category, p.message, p.packageName, p.userId ?: -1)
                }
            } else {
                Log.e("DaemonRepository", "Query 'get_logs' received unexpected response type '${message?.type}'")
                null
            }
        } catch (e: Exception) {
            Log.e("DaemonRepository", "Failed to query 'get_logs': ${e.message}")
            pendingRequests.remove(reqId)
            null
        }
    }


    fun getStatsStream(): Flow<MetricsRecord> = tcpClient.incomingMessages
        .mapNotNull { jsonLine ->
            try {
                val type = object : TypeToken<CerberusMessage<MetricsRecordPayload>>() {}.type
                val msg = gson.fromJson<CerberusMessage<MetricsRecordPayload>>(jsonLine, type)
                if (msg?.type == "stream.new_stats_record") {
                    val p = msg.payload ?: return@mapNotNull null
                    MetricsRecord(
                        timestamp = p.timestamp,
                        cpuUsagePercent = p.cpuUsagePercent,
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
                } else null
            } catch (e: Exception) {
                Log.w("DaemonRepository", "Failed to parse MetricsRecordPayload: ${e.message}")
                null
            }
        }

    suspend fun getAllLogs(): List<LogEntry>? {
        return getLogs(limit = 50)
    }

    suspend fun getHistoryStats(): List<MetricsRecord>? = query("query.get_history_stats") { json ->
        val type = object : TypeToken<List<MetricsRecordPayload>>() {}.type
        val payloads = gson.fromJson<List<MetricsRecordPayload>>(json, type) ?: emptyList()
        payloads.map { p ->
            MetricsRecord(
                timestamp = p.timestamp,
                cpuUsagePercent = p.cpuUsagePercent,
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
    }

    private suspend fun <T> query(queryType: String, payloadParser: (String) -> T): T? {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingRequests[reqId] = deferred

        val requestMsg = CerberusMessage(type = queryType, requestId = reqId, payload = EmptyPayload)
        tcpClient.sendMessage(gson.toJson(requestMsg))

        return try {
            val responseJson = withTimeout(5000) { deferred.await() }
            val expectedResponseType = queryType.replace("query.", "resp.")

            val baseMsg = gson.fromJson(responseJson, BaseMessageWithPayload::class.java)
            if (baseMsg?.type == expectedResponseType && baseMsg.payload != null) {
                payloadParser(baseMsg.payload.toString())
            } else {
                Log.e("DaemonRepository", "Query '$queryType' received unexpected response type '${baseMsg?.type}' or null payload")
                null
            }
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
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingRequests[reqId] = deferred

        val requestMsg = CerberusMessage(type = "query.get_all_policies", requestId = reqId, payload = EmptyPayload)
        tcpClient.sendMessage(gson.toJson(requestMsg))

        return try {
            val responseJson = withTimeout(5000) { deferred.await() }
            val type = object : TypeToken<CerberusMessage<FullConfigPayload>>() {}.type
            val message = gson.fromJson<CerberusMessage<FullConfigPayload>>(responseJson, type)
            if (message?.type == "resp.all_policies") message.payload else null
        } catch (e: Exception) {
            Log.e("DaemonRepository", "Failed to get all policies: ${e.message}")
            pendingRequests.remove(reqId)
            null
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
    private data class BaseMessageWithPayload(
        val type: String,
        @SerializedName("req_id") val requestId: String?,
        val payload: com.google.gson.JsonElement?
    )

    companion object {
        @Volatile
        private var INSTANCE: DaemonRepository? = null

        fun getInstance(scope: CoroutineScope? = null): DaemonRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = INSTANCE
                if (instance != null) {
                    instance
                } else {
                    require(scope != null) { "CoroutineScope must be provided for the first initialization of DaemonRepository" }
                    val newInstance = DaemonRepository(scope)
                    INSTANCE = newInstance
                    newInstance
                }
            }
        }
    }
}