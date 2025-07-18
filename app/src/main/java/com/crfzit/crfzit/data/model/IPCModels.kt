// app/src/main/java/com/crfzit/crfzit/data/model/IPCModels.kt
package com.crfzit.crfzit.data.model

import com.google.gson.annotations.SerializedName

// =======================================================================================
// 通用消息结构
// =======================================================================================

/**
 * UDS上交换的所有消息的通用顶层结构。
 * @param V 协议版本号
 * @param type 消息类型 (e.g., "stream.dashboard_update", "cmd.set_policy")
 * @param reqId 可选，用于请求-响应模式的唯一ID
 * @param payload 消息的具体负载，类型不确定，使用泛型
 */
data class CerberusMessage<T>(
    @SerializedName("v")
    val version: Int,
    val type: String,
    @SerializedName("req_id")
    val requestId: String? = null,
    val payload: T
)

// =======================================================================================
// UI <-> Daemon 核心模型
// =======================================================================================

/**
 * 'stream.dashboard_update' 消息的核心负载模型。
 */
data class DashboardPayload(
    @SerializedName("global_stats")
    val globalStats: GlobalStats,

    @SerializedName("apps_runtime_state")
    val appsRuntimeState: List<AppRuntimeState>
)

/**
 * 全局系统状态模型，对应守护进程中的 GlobalStatsData。
 */
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

/**
 * 单个应用实例的实时运行状态模型，对应守护进程中的 AppRuntimeState。
 * [FIX] 补充缺失的字段以匹配ViewModel的引用和后端JSON。
 */
data class AppRuntimeState(
    @SerializedName("package_name")
    val packageName: String,

    @SerializedName("app_name")
    val appName: String,

    @SerializedName("user_id")
    val userId: Int = 0,

    @SerializedName("display_status")
    val displayStatus: String = "UNKNOWN",

    // [FIX] 确保这两个字段存在，以修复编译错误
    @SerializedName("mem_usage_kb")
    val memUsageKb: Long = 0L,

    @SerializedName("swap_usage_kb")
    val swapUsageKb: Long = 0L,

    // [FIX] 确保这个字段存在，以修复编译错误
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

/**
 * UI向Daemon查询所有策略配置 (`query.get_all_policies`) 的响应负载。
 */
data class PolicyConfigPayload(
    @SerializedName("hard_safety_net")
    val hardSafetyNet: Set<String>,
    val policies: List<AppPolicyPayload>
)

/**
 * 单个应用的持久化策略模型，用于IPC。
 */
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
// Probe <-> Daemon 新增的专用模型
// =======================================================================================

/**
 * Probe向Daemon发送的 `event.app_state_changed` 事件负载。
 */
data class ProbeAppStateChangedPayload(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int,
    @SerializedName("is_foreground")
    val isForeground: Boolean,
    @SerializedName("oom_adj")
    val oomAdj: Int,
    val reason: String
)

/**
 * Probe向Daemon发送的 `event.system_state_changed` 事件负载。
 */
data class ProbeSystemStateChangedPayload(
    @SerializedName("screen_on")
    val screenOn: Boolean? = null,
)

/**
 * Daemon向Probe下发的配置更新 `stream.probe_config_update` 的负载。
 */
data class ProbeConfigUpdatePayload(
    val policies: List<AppPolicyPayload>,
    @SerializedName("frozen_apps")
    val frozenApps: List<AppInstanceKey>
)

/**
 * 应用实例的唯一标识
 */
data class AppInstanceKey(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int
)