package com.crfzit.crfzit.ui.configuration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.Policy
import com.crfzit.crfzit.data.repository.MockAppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConfigurationUiState(
    val isLoading: Boolean = true,
    val allApps: List<AppInfo> = emptyList(),
    val searchQuery: String = "",
    val showSystemApps: Boolean = false
)

class ConfigurationViewModel : ViewModel() {
    private val repository = MockAppRepository() // 使用模拟数据

    private val _uiState = MutableStateFlow(ConfigurationUiState())
    val uiState: StateFlow<ConfigurationUiState> = _uiState.asStateFlow()

    init {
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val apps = repository.getAllApps()
            _uiState.update { it.copy(isLoading = false, allApps = apps) }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onShowSystemAppsChanged(show: Boolean) {
        _uiState.update { it.copy(showSystemApps = show) }
    }

    // 在 ConfigurationViewModel 类中添加以下函数

    fun setPlaybackExemption(packageName: String, isExempt: Boolean) {
        // 在实际应用中，这里会调用 repository 来持久化数据
        _uiState.update { currentState ->
            val updatedApps = currentState.allApps.map {
                if (it.packageName == packageName) it.copy(forcePlaybackExemption = isExempt) else it
            }
            currentState.copy(allApps = updatedApps)
        }
    }

    fun setNetworkExemption(packageName: String, isExempt: Boolean) {
        // 在实际应用中，这里会调用 repository 来持久化数据
        _uiState.update { currentState ->
            val updatedApps = currentState.allApps.map {
                if (it.packageName == packageName) it.copy(forceNetworkExemption = isExempt) else it
            }
            currentState.copy(allApps = updatedApps)
        }
    }

    fun setPolicy(packageName: String, policy: Policy) {
        viewModelScope.launch {
            repository.setPolicyForApp(packageName, policy)
            // 更新UI状态以立即反映变化
            _uiState.update { currentState ->
                val updatedApps = currentState.allApps.map {
                    if (it.packageName == packageName) it.copy(policy = policy) else it
                }
                currentState.copy(allApps = updatedApps)
            }
        }
    }
}