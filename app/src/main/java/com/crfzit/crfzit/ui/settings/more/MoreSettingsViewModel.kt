// app/src/main/java/com/crfzit/crfzit/ui/settings/more/MoreSettingsViewModel.kt
package com.crfzit.crfzit.ui.settings.more

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.AppInstanceKey
import com.crfzit.crfzit.data.model.AppPolicyPayload
import com.crfzit.crfzit.data.repository.DaemonRepository
import com.crfzit.crfzit.ui.configuration.Policy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MoreSettingsUiState(
    val isLoading: Boolean = true,
    val adjRulesContent: String = "正在加载...",
    val adjRulesError: String? = null,
    val dataAppPackages: List<String> = emptyList()
)

class MoreSettingsViewModel(private val app: Application) : AndroidViewModel(app) {
    private val daemonRepository = DaemonRepository.getInstance()

    private val _uiState = MutableStateFlow(MoreSettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadAdjRules()
        loadDataAppPackages()
    }

    private fun loadAdjRules() {
        viewModelScope.launch {
            _uiState.update { it.copy(adjRulesContent = "正在加载...", adjRulesError = null) }
            val content = daemonRepository.getAdjRulesContent()
            if (content != null) {
                _uiState.update { it.copy(adjRulesContent = formatJson(content)) }
            } else {
                _uiState.update { it.copy(adjRulesError = "无法加载OOM策略文件。") }
            }
        }
    }

    private fun loadDataAppPackages() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val packages = daemonRepository.getDataAppPackages() ?: emptyList()
            _uiState.update { it.copy(isLoading = false, dataAppPackages = packages) }
        }
    }

    fun hotReloadOomPolicy() {
        viewModelScope.launch {
            daemonRepository.reloadAdjRules()
            Toast.makeText(app, "热重载指令已发送", Toast.LENGTH_SHORT).show()
            // 重新加载并显示内容
            loadAdjRules()
        }
    }
    
    fun applyBulkPolicy(policy: Policy) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val currentConfig = daemonRepository.getAllPolicies()
            if (currentConfig == null) {
                Toast.makeText(app, "错误：无法获取当前配置", Toast.LENGTH_SHORT).show()
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }

            val targetPackages = _uiState.value.dataAppPackages
            if (targetPackages.isEmpty()) {
                Toast.makeText(app, "没有找到/data/app下的应用", Toast.LENGTH_SHORT).show()
                 _uiState.update { it.copy(isLoading = false) }
                return@launch
            }

            val existingPolicies = currentConfig.policies.associateBy {
                AppInstanceKey(it.packageName, it.userId)
            }.toMutableMap()
            
            for (pkg in targetPackages) {
                // 仅对主用户(user 0)进行操作
                val key = AppInstanceKey(pkg, 0)
                existingPolicies[key] = AppPolicyPayload(pkg, 0, policy.value)
            }

            val newConfig = currentConfig.copy(policies = existingPolicies.values.toList())
            daemonRepository.setPolicy(newConfig)
            _uiState.update { it.copy(isLoading = false) }
            Toast.makeText(app, "已为 ${targetPackages.size} 个应用批量应用'${policy.displayName}'策略", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatJson(jsonString: String): String {
        return try {
            val jsonElement = JsonParser.parseString(jsonString)
            GsonBuilder().setPrettyPrinting().create().toJson(jsonElement)
        } catch (e: Exception) {
            // 如果JSON格式错误，返回原始字符串以便用户查看
            jsonString
        }
    }
}