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
                    Log.e("DaemonRepository", "JSON parse error in response collector: ${e.message} for line: $jsonLine")
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

    // [核心修复] 发送策略时，确保userId也被包含在内
    fun setPolicy(config: AppInfo) {
        val payload = mapOf(
            "package_name" to config.packageName,
            "user_id" to config.userId, // 发送userId
            "policy" to config.policy.value,
            "force_playback_exempt" to config.forcePlaybackExemption,
            "force_network_exempt" to config.forceNetworkExemption
        )
        val message = CerberusMessage(10, "cmd.set_policy", null, payload)
        udsClient.sendMessage(gson.toJson(message))
    }

    // [核心修复] 请求所有策略，并使用正确的PolicyConfigPayload模型解析响应
    suspend fun getAllPolicies(): PolicyConfigPayload? {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingRequests[reqId] = deferred

        val requestMsg = CerberusMessage(10, "query.get_all_policies", reqId, EmptyPayload)
        udsClient.sendMessage(gson.toJson(requestMsg))

        return try {
            val responseJson = withTimeout(5000) { deferred.await() }
            val type = object : TypeToken<CerberusMessage<PolicyConfigPayload>>() {}.type
            val message = gson.fromJson<CerberusMessage<PolicyConfigPayload>>(responseJson, type)
            if (message.type == "resp.all_policies") message.payload else null
        } catch (e: Exception) {
            Log.e("DaemonRepository", "Failed to get all policies: ${e.message}")
            pendingRequests.remove(reqId)
            null
        }
    }

    fun requestDashboardRefresh() {
        val message = CerberusMessage(10, "query.refresh_dashboard", null, EmptyPayload)
        udsClient.sendMessage(gson.toJson(message))
    }

    fun stop() {
        udsClient.stop()
        pendingRequests.values.forEach { it.cancel("Repository is stopping.") }
        pendingRequests.clear()
    }

    private data class BaseMessage(val type: String, @SerializedName("req_id") val requestId: String?)
    private object EmptyPayload
}