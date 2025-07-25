// app/src/main/java/com/crfzit/crfzit/ui/settings/SettingsViewModel.kt
package com.crfzit.crfzit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isLoading: Boolean = true,
    val standardTimeoutSec: Int = 90,
    val isTimedUnfreezeEnabled: Boolean = true,
    val timedUnfreezeIntervalSec: Int = 1800
)

class SettingsViewModel : ViewModel() {
    private val daemonRepository = DaemonRepository(viewModelScope)
    
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val config = daemonRepository.getAllPolicies()
            if (config != null) {
                // [核心修复] 因为 IPCModels.kt 已更新，现在可以直接、安全地访问这些字段
                val masterConfig = config.masterConfig
                 _uiState.update {
                    it.copy(
                        isLoading = false,
                        standardTimeoutSec = masterConfig.standardTimeoutSec,
                        isTimedUnfreezeEnabled = masterConfig.isTimedUnfreezeEnabled,
                        timedUnfreezeIntervalSec = masterConfig.timedUnfreezeIntervalSec
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
    
    fun setStandardTimeout(seconds: Int) {
        // 乐观更新UI
        _uiState.update { it.copy(standardTimeoutSec = seconds) }
        // 将完整配置发送到后端
        sendMasterConfigUpdate()
    }

    fun setTimedUnfreezeEnabled(isEnabled: Boolean) {
        _uiState.update { it.copy(isTimedUnfreezeEnabled = isEnabled) }
        sendMasterConfigUpdate()
    }

    fun setTimedUnfreezeInterval(seconds: Int) {
        _uiState.update { it.copy(timedUnfreezeIntervalSec = seconds) }
        sendMasterConfigUpdate()
    }
    
    private fun sendMasterConfigUpdate() {
        viewModelScope.launch {
            val currentState = _uiState.value
            val payload = mapOf(
                "standard_timeout_sec" to currentState.standardTimeoutSec,
                "is_timed_unfreeze_enabled" to currentState.isTimedUnfreezeEnabled,
                "timed_unfreeze_interval_sec" to currentState.timedUnfreezeIntervalSec
            )
            daemonRepository.setMasterConfig(payload)
        }
    }
    
    override fun onCleared() {
        daemonRepository.stop()
        super.onCleared()
    }
}