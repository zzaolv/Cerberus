// app/src/main/java/com/crfzit/crfzit/data/model/DashboardModels.kt
package com.crfzit.crfzit.data.model

import com.google.gson.annotations.SerializedName

data class CerberusMessage(
    @SerializedName("v")
    val version: Int,
    val type: String,
    @SerializedName("req_id")
    val requestId: String?,
    val payload: DashboardPayload
)

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
    val hasPlayback: Boolean = false,
    val hasNotification: Boolean = false,
    val hasNetworkActivity: Boolean = false,
    val pendingFreezeSec: Int = 0
)

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

enum class FreezeMode {
    CGROUP,
    SIGSTOP,
    UNKNOWN
}