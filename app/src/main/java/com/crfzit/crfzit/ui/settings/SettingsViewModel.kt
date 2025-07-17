// app/src/main/java/com/crfzit/crfzit/ui/settings/SettingsViewModel.kt
package com.crfzit.crfzit.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.CerberusApplication
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appScope = (application as CerberusApplication).applicationScope
    private val udsClient = UdsClient.getInstance(appScope)
    private val gson = Gson()

    private val _settingsState = MutableStateFlow(SettingsState())
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    private val _healthCheckState = MutableStateFlow(HealthCheckState())
    val healthCheckState: StateFlow<HealthCheckState> = _healthCheckState.asStateFlow()

    init {
        observeHealthCheckResponse()
    }
    
    @Suppress("UNCHECKED_CAST")
    private fun observeHealthCheckResponse() {
        viewModelScope.launch {
            udsClient.incomingMessages.filter { it.contains("resp.health_check") }.collect { jsonLine ->
                try {
                    val response = gson.fromJson(jsonLine, Map::class.java)
                    val payload = response["payload"] as? Map<String, Any>
                    _healthCheckState.update {
                        it.copy(
                            daemonPid = (payload?.get("daemon_pid") as? Double)?.toInt() ?: -1,
                            isProbeConnected = payload?.get("is_probe_connected") as? Boolean ?: false,
                            isLoading = false
                        )
                    }
                } catch (e: Exception) {
                    Log.e("SettingsVM", "Error parsing health check response: ", e)
                    _healthCheckState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun onFreezerTypeChanged(type: FreezerType) {
        _settingsState.update { it.copy(freezerType = type) }
        sendSettingsUpdate()
    }

    fun onUnfreezeIntervalChanged(minutes: Int) {
        _settingsState.update { it.copy(unfreezeIntervalMinutes = minutes) }
        sendSettingsUpdate()
    }
    
    private fun sendSettingsUpdate() {
        val payload = mapOf(
            "freezer_type" to _settingsState.value.freezerType.ordinal,
            "unfreeze_interval" to _settingsState.value.unfreezeIntervalMinutes
        )
        val message = mapOf("v" to 1, "type" to "cmd.set_settings", "payload" to payload)
        udsClient.sendMessage(gson.toJson(message))
    }
    
    fun performHealthCheck() {
        _healthCheckState.update { it.copy(isLoading = true) }
        val request = mapOf("v" to 1, "type" to "query.health_check", "req_id" to UUID.randomUUID().toString())
        udsClient.sendMessage(gson.toJson(request))
    }

    fun restartDaemon() {
        val request = mapOf("v" to 1, "type" to "cmd.restart_daemon")
        udsClient.sendMessage(gson.toJson(request))
    }
    
    fun clearStats() {
        val request = mapOf("v" to 1, "type" to "cmd.clear_stats")
        udsClient.sendMessage(gson.toJson(request))
    }
}