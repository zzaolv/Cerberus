// app/src/main/java/com/crfzit/crfzit/ui/settings/SettingsViewModel.kt (新建)
package com.crfzit.crfzit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val standardTimeoutSec: Int = 90
)

class SettingsViewModel : ViewModel() {
    private val daemonRepository = DaemonRepository(viewModelScope)
    
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    fun setStandardTimeout(seconds: Int) {
        _uiState.update { it.copy(standardTimeoutSec = seconds) }
        viewModelScope.launch {
            val payload = mapOf("standard_timeout_sec" to seconds)
            // Note: This requires a new method in DaemonRepository
            daemonRepository.setMasterConfig(payload)
        }
    }
    
    // TODO: Add a method in init to load the current config from daemon.
    // For now, it will just show the default.
    
    override fun onCleared() {
        daemonRepository.stop()
        super.onCleared()
    }
}