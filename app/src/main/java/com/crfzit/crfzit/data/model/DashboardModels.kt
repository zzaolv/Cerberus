// app/src/main/java/com/crfzit/crfzit/data/model/DashboardModels.kt
package com.crfzit.crfzit.data.model

import com.google.gson.annotations.SerializedName

/**
 * 对应 dashboard_update 消息的顶层结构 (根据技术文档的IPC协议调整)
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
 * 对应 stream.dashboard_update 的 payload
 */
data class DashboardPayload(
    @SerializedName("global_stats")
    val globalStats: GlobalStats,
    @SerializedName("apps_runtime_state")
    val appsRuntimeState: List<AppRuntimeState>
)

/**
 * 全局状态的数据模型
 */
data class GlobalStats(
    @SerializedName("total_cpu_usage_percent")
    val totalCpuUsagePercent: Float = 0f,
    @SerializedName("total_mem_kb")
    val totalMemKb: Long = 0L,
    @SerializedName("avail_mem_kb")
    val availMemKb: Long = 0L,
    // 【修改】属性名改为驼峰式
    @SerializedName("net_down_speed_bps")
    val netDownSpeedBps: Long = 0L,
    @SerializedName("net_up_speed_bps")
    val netUpSpeedBps: Long = 0L,
    @SerializedName("active_profile_name")
    val activeProfileName: String = "等待连接..."
)

/**
 * 单个应用运行时状态的数据模型
 */
data class AppRuntimeState(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("app_name")
    val appName: String,
    // 【新增】
    @SerializedName("user_id")
    val userId: Int = 0,
    @SerializedName("display_status")
    val displayStatus: DisplayStatus = DisplayStatus.UNKNOWN,
    @SerializedName("active_freeze_mode")
    val activeFreezeMode: FreezeMode? = null,
    @SerializedName("mem_usage_kb")
    val memUsageKb: Long = 0L,
    @SerializedName("cpu_usage_percent")
    val cpuUsagePercent: Float = 0f,
    @SerializedName("is_whitelisted")
    val isWhitelisted: Boolean = false,
    @SerializedName("is_foreground")
    val isForeground: Boolean = false,
    // 以下为模拟状态，在真实实现中会从 Observer 获取
    val hasPlayback: Boolean = false,
    val hasNotification: Boolean = false,
    val hasNetworkActivity: Boolean = false,
    val pendingFreezeSec: Int = 0
)

/**
 * 应用显示状态的枚举 (与文档保持一致)
 */
enum class DisplayStatus {
    STOPPED, // 新增
    FOREGROUND,
    FOREGROUND_GAME, // 补充文档中的状态
    BACKGROUND_ACTIVE,
    BACKGROUND_IDLE,
    AWAITING_FREEZE,
    FROZEN,
    KILLED, // 补充文档中的状态
    EXEMPTED, // 补充文档中的状态
    UNKNOWN
}

/**
 * 冻结模式的枚举
 */
enum class FreezeMode {
    CGROUP,
    SIGSTOP,
    UNKNOWN
}