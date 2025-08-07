// app/src/main/java/com/crfzit/crfzit/data/model/IPCModels.kt
package com.crfzit.crfzit.data.model

import com.google.gson.annotations.SerializedName

data class CerberusMessage<T>(
    @SerializedName("v")
    val version: Int = 12,
    val type: String,
    @SerializedName("req_id")
    val requestId: String? = null,
    val payload: T
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
    @SerializedName("swap_total_kb")
    val swapTotalKb: Long = 0L,
    @SerializedName("swap_free_kb")
    val swapFreeKb: Long = 0L
)

// [核心修正] 在 AppRuntimeState 中添加新的状态字段
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
    // 这三个是上次更新后端时新增的，现在在前端模型中补上
    @SerializedName("is_playing_audio")
    val isPlayingAudio: Boolean = false,
    @SerializedName("is_using_location")
    val isUsingLocation: Boolean = false,
    @SerializedName("has_high_network_usage")
    val hasHighNetworkUsage: Boolean = false
)

// --- Probe -> Daemon 事件模型 ---
data class AppStateEventPayload(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int
)

// --- 配置模型 (UI -> Daemon -> Probe) ---
data class FullConfigPayload(
    @SerializedName("master_config")
    val masterConfig: MasterConfig,
    @SerializedName("exempt_config")
    val exemptConfig: ExemptConfig,
    @SerializedName("policies")
    val policies: List<AppPolicyPayload>,
    @SerializedName("frozen_uids")
    val frozenUids: List<Int>? = null
)

data class MasterConfig(
    @SerializedName("is_enabled")
    val isEnabled: Boolean = true,
    @SerializedName("freeze_on_screen_off")
    val freezeOnScreenOff: Boolean = true,
    @SerializedName("standard_timeout_sec")
    val standardTimeoutSec: Int = 90,
    @SerializedName("is_timed_unfreeze_enabled")
    val isTimedUnfreezeEnabled: Boolean = true,
    @SerializedName("timed_unfreeze_interval_sec")
    val timedUnfreezeIntervalSec: Int = 1800
)

data class ExemptConfig(
    @SerializedName("exempt_foreground_services")
    val exemptForegroundServices: Boolean = true
)

// [核心修改] 在 AppPolicyPayload 中添加新的豁免字段
data class AppPolicyPayload(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int,
    val policy: Int, // Corresponds to AppPolicy enum
    // 新增字段，使用 @SerializedName 确保JSON key正确
    // 设为可空并提供默认值，以兼容旧版守护进程可能不返回这些字段的情况
    @SerializedName("force_playback_exemption")
    val forcePlaybackExemption: Boolean? = null,
    @SerializedName("force_network_exemption")
    val forceNetworkExemption: Boolean? = null,
    @SerializedName("force_location_exemption")
    val forceLocationExemption: Boolean? = null,
    @SerializedName("allow_timed_unfreeze")
    val allowTimedUnfreeze: Boolean? = null
)

data class AppInstanceKey(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int
)

// ... (LogEntryPayload, MetricsRecordPayload, GetLogsPayload 保持不变) ...
data class LogEntryPayload(
    val timestamp: Long,
    val level: Int,
    val category: String,
    val message: String,
    @SerializedName("package_name") val packageName: String?,
    @SerializedName("user_id") val userId: Int?
)

data class MetricsRecordPayload(
    val timestamp: Long,
    @SerializedName("cpu_usage_percent") val totalCpuUsagePercent: Float,
    @SerializedName("per_core_cpu_usage_percent") val perCoreCpuUsagePercent: List<Float>?,
    @SerializedName("mem_total_kb") val memTotalKb: Long,
    @SerializedName("mem_available_kb") val memAvailableKb: Long,
    @SerializedName("swap_total_kb") val swapTotalKb: Long,
    @SerializedName("swap_free_kb") val swapFreeKb: Long,
    @SerializedName("battery_level") val batteryLevel: Int,
    @SerializedName("battery_temp_celsius") val batteryTempCelsius: Float,
    @SerializedName("battery_power_watt") val batteryPowerWatt: Float,
    @SerializedName("is_charging") val isCharging: Boolean,
    @SerializedName("is_screen_on") val isScreenOn: Boolean,
    @SerializedName("is_audio_playing") val isAudioPlaying: Boolean,
    @SerializedName("is_location_active") val isLocationActive: Boolean
)

data class GetLogsPayload(
    val since: Long? = null,
    val before: Long? = null,
    val limit: Int? = null
)