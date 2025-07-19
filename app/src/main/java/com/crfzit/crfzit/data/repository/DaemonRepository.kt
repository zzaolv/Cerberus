// app/src/main/java/com/crfzit/crfzit/data/repository/DaemonRepository.kt
package com.crfzit.crfzit.data.repository

import android.util.Log
import com.crfzit.crfzit.data.model.* // 导入所有模型
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
                    // 只处理响应消息，流消息由各自的收集器处理
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

    /**
     * 获取仪表盘数据流
     */
    fun getDashboardStream(): Flow<DashboardPayload> = udsClient.incomingMessages
        .mapNotNull { jsonLine ->
            try {
                // 使用 TypeToken 精确解析泛型
                val type = object : TypeToken<CerberusMessage<DashboardPayload>>() {}.type
                val msg = gson.fromJson<CerberusMessage<DashboardPayload>>(jsonLine, type)
                // 确保消息类型正确
                if (msg.type == "stream.dashboard_update") msg.payload else null
            } catch (e: Exception) {
                // 忽略解析失败的消息（可能是其他类型的消息）
                null
            }
        }

    /**
     * 设置单个应用的策略配置
     */
    fun setPolicy(config: AppInfo) {
        val payload = mapOf(
            "package_name" to config.packageName,
            "user_id" to config.userId,
            "policy" to config.policy.value,
            "force_playback_exempt" to config.forcePlaybackExemption,
            "force_network_exempt" to config.forceNetworkExemption
        )
        // 使用v2协议
        val message = CerberusMessage(2, "cmd.set_policy", null, payload)
        udsClient.sendMessage(gson.toJson(message))
    }

    /**
     * [请求-响应模式] 获取所有应用的完整策略配置
     */
    suspend fun getAllPolicies(): PolicyConfigPayload? {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingRequests[reqId] = deferred

        // 使用v2协议
        val requestMsg = CerberusMessage(2, "query.get_all_policies", reqId, EmptyPayload)
        udsClient.sendMessage(gson.toJson(requestMsg))

        return try {
            // 设置5秒超时
            val responseJson = withTimeout(5000) { deferred.await() }
                val type = object : TypeToken<CerberusMessage<PolicyConfigPayload>>() {}.type
            val message = gson.fromJson<CerberusMessage<PolicyConfigPayload>>(responseJson, type)
            // 确保响应类型匹配
            if (message.type == "resp.all_policies") message.payload else null
        } catch (e: Exception) {
            Log.e("DaemonRepository", "Failed to get all policies: ${e.message}")
            pendingRequests.remove(reqId)
            null
        }
    }

    /**
     * 请求守护进程立即刷新一次仪表盘数据
     */
    fun requestDashboardRefresh() {
        val message = CerberusMessage(2, "query.refresh_dashboard", null, EmptyPayload)
        udsClient.sendMessage(gson.toJson(message))
    }

    /**
     * 停止UDS客户端并清理资源
     */
    fun stop() {
        udsClient.stop()
        // 取消所有待处理的请求
        pendingRequests.values.forEach { it.cancel("Repository is stopping.") }
        pendingRequests.clear()
    }

    // 内部辅助类
    private data class BaseMessage(val type: String, @SerializedName("req_id") val requestId: String?)
    private object EmptyPayload
}

// [REMOVED] PolicyConfigPayload 和 AppPolicyPayload 已移至 IPCModels.kt