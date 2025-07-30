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
    // 架构重构：获取唯一的单例实例
    private val daemonRepository = DaemonRepository.getInstance()
    
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
        _uiState.update { it.copy(standardTimeoutSec = seconds) }
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
        super.onCleared()
        // 架构重构：ViewModel不再负责停止Repository
    }
}