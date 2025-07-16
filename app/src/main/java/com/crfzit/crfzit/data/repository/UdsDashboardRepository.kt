// app/src/main/java/com/crfzit/crfzit/data/repository/UdsDashboardRepository.kt
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

    // 【核心修复】通过 getInstance() 获取全局单例，不再自己创建
    private val udsClient = UdsClient.getInstance(scope)
    private val gson = Gson()

    private val dashboardUpdateFlow: SharedFlow<CerberusMessage> = flow {
        Log.i("UdsDashboardRepo", "UDS message flow is now active. Listening to shared UDS client...")

        // 【核心修复】不再需要调用 udsClient.start()，它在Application创建时已自动启动
        // udsClient.start()

        udsClient.incomingMessages.collect { jsonLine ->
            try {
                // 使用更健壮的解析方式
                val msg = gson.fromJson(jsonLine, Map::class.java)
                if (msg["type"] == "stream.dashboard_update") {
                    val message = gson.fromJson(jsonLine, CerberusMessage::class.java)
                    emit(message)
                }
            } catch (e: JsonSyntaxException) {
                Log.e("UdsDashboardRepo", "JSON parse error: ${e.message} \\n\\t for line: $jsonLine")
            } catch (e: Exception) {
                Log.e("UdsDashboardRepo", "General error processing message: ${e.message}")
            }
        }
    }.flowOn(Dispatchers.IO)
        .shareIn(scope, SharingStarted.WhileSubscribed(5000), replay = 1)

    override fun getGlobalStatsStream(): Flow<GlobalStats> {
        return dashboardUpdateFlow
            .map { it.payload.globalStats }
            .distinctUntilChanged()
    }

    override fun getAppRuntimeStateStream(): Flow<List<AppRuntimeState>> {
        return dashboardUpdateFlow
            .map { it.payload.appsRuntimeState }
    }
}