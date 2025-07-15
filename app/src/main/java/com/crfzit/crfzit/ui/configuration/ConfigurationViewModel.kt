package com.crfzit.crfzit.ui.configuration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.Policy
// import com.crfzit.crfzit.data.repository.MockAppRepository // 不再使用
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
    // private val repository = MockAppRepository() // <-- 关键修正：移除模拟仓库

    private val _uiState = MutableStateFlow(ConfigurationUiState())
    val uiState: StateFlow<ConfigurationUiState> = _uiState.asStateFlow()

    init {
        // TODO: 未来在这里调用真实仓库加载应用列表
        // loadApps()
        // 目前，我们让它显示加载状态，或者一个空列表
        _uiState.update { it.copy(isLoading = false) } // 暂时设为加载完成，显示空列表
    }

    // loadApps, setPolicy 等函数暂时保留，但它们操作的是内存中的状态
    // 这样UI交互仍然可以工作，只是数据不会持久化
    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onShowSystemAppsChanged(show: Boolean) {
        _uiState.update { it.copy(showSystemApps = show) }
    }

    fun setPolicy(packageName: String, policy: Policy) {
        _uiState.update { currentState ->
            val updatedApps = currentState.allApps.map {
                if (it.packageName == packageName) it.copy(policy = policy) else it
            }
            currentState.copy(allApps = updatedApps)
        }
    }

    fun setPlaybackExemption(packageName: String, isExempt: Boolean) {
        _uiState.update { currentState ->
            val updatedApps = currentState.allApps.map {
                if (it.packageName == packageName) it.copy(forcePlaybackExemption = isExempt) else it
            }
            currentState.copy(allApps = updatedApps)
        }
    }

    fun setNetworkExemption(packageName: String, isExempt: Boolean) {
        _uiState.update { currentState ->
            val updatedApps = currentState.allApps.map {
                if (it.packageName == packageName) it.copy(forceNetworkExemption = isExempt) else it
            }
            currentState.copy(allApps = updatedApps)
        }
    }
}