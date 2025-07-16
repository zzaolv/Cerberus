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

    private val dashboardUpdateFlow: SharedFlow<CerberusMessage> = flow {
        Log.i("UdsDashboardRepo", "UDS message flow is now active. Starting UDS client...")
        udsClient.start()
        udsClient.incomingMessages.collect { jsonLine ->
            try {
                val message = gson.fromJson(jsonLine, CerberusMessage::class.java)
                if (message.type == "stream.dashboard_update") {
                    emit(message)
                }
            } catch (e: JsonSyntaxException) {
                Log.e("UdsDashboardRepo", "JSON parse error: ${e.message} \\n\\t for line: $jsonLine")
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