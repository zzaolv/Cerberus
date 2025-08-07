// app/src/main/java/com/crfzit/crfzit/ui/settings/more/MoreSettingsViewModel.kt
package com.crfzit.crfzit.ui.settings.more

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.AppInstanceKey
import com.crfzit.crfzit.data.model.AppPolicyPayload
import com.crfzit.crfzit.data.model.Policy
import com.crfzit.crfzit.data.repository.DaemonRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MoreSettingsUiState(
    val isLoading: Boolean = true,
    // [核心修改] 使用新的数据模型
    val oomRules: List<OomRule> = emptyList(),
    val adjRulesError: String? = null,
    val dataAppPackages: List<String> = emptyList()
)

class MoreSettingsViewModel(private val app: Application) : AndroidViewModel(app) {
    private val daemonRepository = DaemonRepository.getInstance()
    // [核心修改] 使用 Gson 进行序列化和反序列化
    private val gson = GsonBuilder().setPrettyPrinting().create()


    private val _uiState = MutableStateFlow(MoreSettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadAdjRules()
        loadDataAppPackages()
    }

    private fun loadAdjRules() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, adjRulesError = null) }
            val content = daemonRepository.getAdjRulesContent()
            if (content != null && content.isNotBlank()) {
                try {
                    val rulesFile = gson.fromJson(content, AdjRulesFile::class.java)
                    // 排序规则以确保UI显示正确
                    val sortedRules = rulesFile.rules.sortedBy { it.sourceRange.firstOrNull() ?: 0 }
                    _uiState.update { it.copy(isLoading = false, oomRules = sortedRules) }
                } catch (e: Exception) {
                    _uiState.update { it.copy(isLoading = false, adjRulesError = "无法解析OOM策略文件: ${e.message}") }
                }
            } else {
                _uiState.update { it.copy(isLoading = false, adjRulesError = "无法加载OOM策略文件或文件为空。") }
            }
        }
    }

    // [核心新增] 更新单条规则
    fun updateRule(updatedRule: OomRule) {
        _uiState.update { state ->
            val updatedList = state.oomRules.map {
                if (it.id == updatedRule.id) updatedRule else it
            }
            state.copy(oomRules = updatedList)
        }
    }

    // [核心新增] 添加新规则
    fun addNewRule() {
        _uiState.update { state ->
            val lastSourceMax = state.oomRules.lastOrNull()?.sourceRange?.getOrNull(1) ?: 900
            val newRule = OomRule(
                sourceRange = listOf(lastSourceMax + 1, lastSourceMax + 100),
                type = "linear",
                targetRange = listOf(0, 0)
            )
            val newList = (state.oomRules + newRule).sortedBy { it.sourceRange.firstOrNull() ?: 0 }
            state.copy(oomRules = newList)
        }
    }

    // [核心新增] 删除规则
    fun deleteRule(ruleId: String) {
        _uiState.update { state ->
            val updatedList = state.oomRules.filterNot { it.id == ruleId }
            state.copy(oomRules = updatedList)
        }
    }

    // [核心新增] 保存并热重载所有规则
    fun saveAndReloadRules() {
        viewModelScope.launch {
            val rulesToSave = _uiState.value.oomRules
            // 在保存前，根据类型清理掉不必要的字段
            val cleanedRules = rulesToSave.map {
                when (it.type) {
                    "linear" -> it.copy(params = null, targetRange = it.targetRange ?: listOf(0, 0))
                    "sigmoid" -> it.copy(targetRange = null, params = it.params ?: SigmoidParams())
                    else -> it
                }
            }
            val rulesFile = AdjRulesFile(rules = cleanedRules)
            val jsonContent = gson.toJson(rulesFile)
            daemonRepository.setAdjRulesContent(jsonContent) // 这个新方法需要在Repository中添加
            Toast.makeText(app, "OOM策略已保存并发送热重载指令", Toast.LENGTH_SHORT).show()
        }
    }


    private fun loadDataAppPackages() { /* ... 此函数保持不变 ... */
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val packages = daemonRepository.getDataAppPackages() ?: emptyList()
            _uiState.update { it.copy(isLoading = false, dataAppPackages = packages) }
        }
    }

    fun applyBulkPolicy(policy: Policy) { /* ... 此函数保持不变 ... */
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
                val key = AppInstanceKey(pkg, 0)
                existingPolicies[key] = AppPolicyPayload(
                    packageName = pkg,
                    userId = 0,
                    policy = policy.value
                )
            }

            val newConfig = currentConfig.copy(policies = existingPolicies.values.toList())
            daemonRepository.setPolicy(newConfig)
            _uiState.update { it.copy(isLoading = false) }
            Toast.makeText(app, "已为 ${targetPackages.size} 个应用批量应用'${policy.displayName}'策略", Toast.LENGTH_LONG).show()
        }
    }
}