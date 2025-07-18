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

/**
 * 封装所有与守护进程 (`cerberusd`) 的通信逻辑。
 * 这是 UI ViewModel 的唯一数据源。
 */
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
                    Log.e("DaemonRepository", "JSON parse error: ${e.message} for line: $jsonLine")
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

    fun setPolicy(config: AppInfo) {
        val payload = mapOf(
            "package_name" to config.packageName,
            "user_id" to config.userId,
            "policy" to config.policy.value,
            "force_playback_exempt" to config.forcePlaybackExemption,
            "force_network_exempt" to config.forceNetworkExemption
        )
        val message = CerberusMessage(1, "cmd.set_policy", null, payload)
        udsClient.sendMessage(gson.toJson(message))
    }

    suspend fun getAllPolicies(): PolicyConfigPayload? {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingRequests[reqId] = deferred

        val requestMsg = CerberusMessage(1, "query.get_all_policies", reqId, EmptyPayload)
        udsClient.sendMessage(gson.toJson(requestMsg))

        return try {
            val responseJson = withTimeout(5000) { deferred.await() }
            val type = object : TypeToken<CerberusMessage<PolicyConfigPayload>>() {}.type
            val message = gson.fromJson<CerberusMessage<PolicyConfigPayload>>(responseJson, type)
            message.payload
        } catch (e: Exception) {
            Log.e("DaemonRepository", "Failed to get all policies: ${e.message}")
            pendingRequests.remove(reqId)
            null
        }
    }

    fun requestDashboardRefresh() {
        val message = CerberusMessage(1, "query.refresh_dashboard", null, EmptyPayload)
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

data class PolicyConfigPayload(
    @SerializedName("hard_safety_net")
    val hardSafetyNet: Set<String>,
    val policies: List<AppPolicyPayload>
)

data class AppPolicyPayload(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int,
    val policy: Int,
    @SerializedName("force_playback_exempt")
    val forcePlaybackExempt: Boolean,
    @SerializedName("force_network_exempt")
    val forceNetworkExempt: Boolean
)