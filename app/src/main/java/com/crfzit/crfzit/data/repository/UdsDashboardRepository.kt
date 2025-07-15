package com.crfzit.crfzit.data.repository

import android.util.Log
import com.crfzit.crfzit.data.model.AppRuntimeState
import com.crfzit.crfzit.data.model.CerberusMessage
import com.crfzit.crfzit.data.model.GlobalStats
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

class UdsDashboardRepository(
    scope: CoroutineScope
) : DashboardRepository {

    private val udsClient = UdsClient(scope)
    private val gson = Gson()

    // 共享的数据流，用于解析所有来自daemon的消息
    private val dashboardUpdateFlow: SharedFlow<CerberusMessage> = flow {
        udsClient.start() // 启动UDS客户端连接和重连逻辑
        udsClient.incomingMessages.collect { jsonLine ->
            try {
                // 使用我们更新后的顶层消息模型进行解析
                val message = gson.fromJson(jsonLine, CerberusMessage::class.java)
                // 只处理 stream.dashboard_update 类型的消息
                if (message.type == "stream.dashboard_update") {
                    emit(message)
                }
            } catch (e: JsonSyntaxException) {
                Log.e("UdsDashboardRepo", "JSON parse error: ${e.message} \n\t for line: $jsonLine")
            }
        }
    }.flowOn(Dispatchers.IO)
     .shareIn(scope, SharingStarted.WhileSubscribed(5000), replay = 1)


    override fun getGlobalStatsStream(): Flow<GlobalStats> {
        return dashboardUpdateFlow
            .map { it.payload.globalStats }
            .distinctUntilChanged() // 只有在数据变化时才发出
    }

    override fun getAppRuntimeStateStream(): Flow<List<AppRuntimeState>> {
        return dashboardUpdateFlow
            .map { it.payload.appsRuntimeState }
             // 不使用 distinctUntilChanged，因为列表内容可能变化但对象引用不变
    }
}