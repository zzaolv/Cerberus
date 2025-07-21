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
}