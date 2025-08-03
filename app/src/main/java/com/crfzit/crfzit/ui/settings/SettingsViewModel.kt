// app/src/main/java/com/crfzit/crfzit/ui/settings/SettingsViewModel.kt
package com.crfzit.crfzit.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.BuildConfig // 导入自动生成的BuildConfig
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class SettingsUiState(
    val isLoading: Boolean = true,
    val standardTimeoutSec: Int = 90,
    val isTimedUnfreezeEnabled: Boolean = true,
    val timedUnfreezeIntervalSec: Int = 1800,
    // [核心新增] 新增用于显示构建时间的字段
    val buildTime: String = "N/A"
)

class SettingsViewModel(private val app: Application) : AndroidViewModel(app) {
    private val daemonRepository = DaemonRepository.getInstance()

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    // [核心新增] 定义原神相关的常量
    private companion object {
        const val GENSHIN_PACKAGE_NAME = "com.miHoYo.GenshinImpact"
        const val GENSHIN_URL = "https://ys.mihoyo.com/main/"
    }

    init {
        loadSettings()
        loadBuildInfo() // 在初始化时加载构建信息
    }

    private fun loadBuildInfo() {
        // [核心新增] 从BuildConfig获取时间戳并格式化
        try {
            val buildTimestamp = BuildConfig.BUILD_TIME
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            _uiState.update { it.copy(buildTime = dateFormat.format(Date(buildTimestamp))) }
        } catch (e: Exception) {
            // In case of any error, keep the default "N/A"
        }
    }

    // [核心新增] 处理GitHub图标点击事件的恶趣味逻辑
    fun onGitHubIconClicked() {
        val packageManager = app.packageManager
        val launchIntent = packageManager.getLaunchIntentForPackage(GENSHIN_PACKAGE_NAME)

        if (launchIntent != null) {
            // 如果安装了原神，启动它！
            Toast.makeText(app, "正在启动...", Toast.LENGTH_SHORT).show()
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(launchIntent)
        } else {
            // 如果没安装，跳转到官网
            Toast.makeText(app, "未检测到应用，正在前往官网...", Toast.LENGTH_SHORT).show()
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(GENSHIN_URL))
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(browserIntent)
        }
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
}