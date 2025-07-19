// app/src/main/java/com/crfzit/crfzit/data/model/IPCModels.kt
package com.crfzit.crfzit.data.model

import com.google.gson.annotations.SerializedName

// =======================================================================================
// 通用消息结构 (无变化)
// =======================================================================================
data class CerberusMessage<T>(
    @SerializedName("v")
    val version: Int,
    val type: String,
    @SerializedName("req_id")
    val requestId: String? = null,
    val payload: T
)

// =======================================================================================
// UI <-> Daemon 核心模型 (无变化)
// =======================================================================================
data class DashboardPayload(
    @SerializedName("global_stats")
    val globalStats: GlobalStats,
    @SerializedName("apps_runtime_state")
    val appsRuntimeState: List<AppRuntimeState>
)

data class GlobalStats(
    @SerializedName("total_cpu_usage_percent")
    val totalCpuUsagePercent: Float = 0f,
    @SerializedName("total_mem_kb")
    val totalMemKb: Long = 0L,
    @SerializedName("avail_mem_kb")
    val availMemKb: Long = 0L,
    @SerializedName("swap_total_kb")
    val swapTotalKb: Long = 0L,
    @SerializedName("swap_free_kb")
    val swapFreeKb: Long = 0L,
    @SerializedName("active_profile_name")
    val activeProfileName: String = "等待连接..."
)

data class AppRuntimeState(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("app_name")
    val appName: String,
    @SerializedName("user_id")
    val userId: Int = 0,
    @SerializedName("display_status")
    val displayStatus: String = "UNKNOWN",
    @SerializedName("mem_usage_kb")
    val memUsageKb: Long = 0L,
    @SerializedName("swap_usage_kb")
    val swapUsageKb: Long = 0L,
    @SerializedName("cpu_usage_percent")
    val cpuUsagePercent: Float = 0f,
    @SerializedName("is_whitelisted")
    val isWhitelisted: Boolean = false,
    @SerializedName("is_foreground")
    val isForeground: Boolean = false,
    val hasPlayback: Boolean = false,
    val hasNotification: Boolean = false,
    val hasNetworkActivity: Boolean = false,
    @SerializedName("pendingFreezeSec")
    val pendingFreezeSec: Int = 0
)

data class PolicyConfigPayload(
    @SerializedName("hard_safety_net")
    val hardSafetyNet: Set<String>,
    val policies: List<AppPolicyPayload>
)

data class AppPolicyPayload(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int,
    val policy: Int,
    @SerializedName("force_playback_exempt")
    val forcePlaybackExempt: Boolean,
    @SerializedName("force_network_exempt")
    val forceNetworkExempt: Boolean
)

// =======================================================================================
// Probe <-> Daemon 模型 (已重构)
// =======================================================================================

/**
 * [REFACTORED] Probe向Daemon发送的 `event.app_state_changed` 事件负载。
 * 现在包含更精确的状态信息，使Daemon可以做出更准确的反应。
 */
data class ProbeAppStateChangedPayload(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int,
    @SerializedName("is_foreground")
    val isForeground: Boolean,
    @SerializedName("is_cached") // [NEW] 明确告知Daemon该进程是否已进入缓存状态
    val isCached: Boolean,
    val reason: String
)

/**
 * Probe向Daemon发送的 `event.system_state_changed` 事件负载 (无变化)。
 */
data class ProbeSystemStateChangedPayload(
    @SerializedName("screen_on")
    val screenOn: Boolean? = null,
)

/**
 * Daemon向Probe下发的配置更新 `stream.probe_config_update` 的负载 (无变化)。
 */
data class ProbeConfigUpdatePayload(
    val policies: List<AppPolicyPayload>,
    @SerializedName("frozen_apps")
    val frozenApps: List<AppInstanceKey>
)

/**
 * 应用实例的唯一标识 (无变化)
 */
data class AppInstanceKey(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int
)