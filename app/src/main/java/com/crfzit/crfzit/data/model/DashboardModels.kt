// app/src/main/java/com/crfzit/crfzit/data/model/DashboardModels.kt
package com.crfzit.crfzit.data.model

import com.google.gson.annotations.SerializedName

/**
 * UDS上交换消息的顶层结构
 */
data class CerberusMessage(
    @SerializedName("v")
    val version: Int,
    val type: String,
    @SerializedName("req_id")
    val requestId: String?,
    val payload: DashboardPayload
)

/**
 * 'stream.dashboard_update' 消息的核心负载
 */
data class DashboardPayload(
    @SerializedName("global_stats")
    val globalStats: GlobalStats,
    @SerializedName("apps_runtime_state")
    val appsRuntimeState: List<AppRuntimeState>
)

/**
 * 全局系统状态
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
 * 单个应用实例的实时运行状态
 */
data class AppRuntimeState(
    @SerializedName("package_name")
    val packageName: String,

    @SerializedName("app_name")
    val appName: String,

    @SerializedName("user_id")
    val userId: Int = 0, // 核心字段：用于区分主应用(0)和分身(>0)

    @SerializedName("display_status")
    val displayStatus: DisplayStatus = DisplayStatus.UNKNOWN,

    @SerializedName("active_freeze_mode")
    val activeFreezeMode: FreezeMode? = null,

    @SerializedName("mem_usage_kb")
    val memUsageKb: Long = 0L, // VmRSS, 常驻内存

    @SerializedName("swap_usage_kb")
    val swapUsageKb: Long = 0L, // VmSwap, 应用占用的交换空间

    @SerializedName("cpu_usage_percent")
    val cpuUsagePercent: Float = 0f,

    @SerializedName("is_whitelisted")
    val isWhitelisted: Boolean = false,

    @SerializedName("is_foreground")
    val isForeground: Boolean = false,

    val hasPlayback: Boolean = false,
    val hasNotification: Boolean = false,
    val hasNetworkActivity: Boolean = false,
    val pendingFreezeSec: Int = 0
)

/**
 * UI上显示的应用状态枚举
 */
enum class DisplayStatus {
    STOPPED,
    FOREGROUND,
    FOREGROUND_GAME,
    BACKGROUND_ACTIVE,
    BACKGROUND_IDLE,
    AWAITING_FREEZE,
    FROZEN,
    KILLED,
    EXEMPTED,
    UNKNOWN
}

/**
 * 冻结模式枚举
 */
enum class FreezeMode {
    CGROUP,
    SIGSTOP,
    UNKNOWN
}