// app/src/main/java/com/crfzit/crfzit/data/model/IPCModels.kt
package com.crfzit.crfzit.data.model

import com.google.gson.annotations.SerializedName

// --- 通用消息结构 ---
data class CerberusMessage<T>(
    @SerializedName("v")
    val version: Int = 12,
    val type: String,
    @SerializedName("req_id")
    val requestId: String? = null,
    val payload: T
)

// --- UI <-> Daemon 模型 ---
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
    val isForeground: Boolean = false
)

// --- Probe <-> Daemon 新指令模型 ---
data class ProbeFreezePayload(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int,
    val pid: Int,
    val uid: Int
)

data class ProbeUnfreezePayload(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int,
    val pid: Int,
    val uid: Int
)


// --- 配置模型 (UI -> Daemon -> Probe) ---
data class FullConfigPayload(
    @SerializedName("master_config")
    val masterConfig: MasterConfig,
    @SerializedName("exempt_config")
    val exemptConfig: ExemptConfig,
    @SerializedName("policies")
    val policies: List<AppPolicyPayload>
)

data class MasterConfig(
    @SerializedName("is_enabled")
    val isEnabled: Boolean = true,
    @SerializedName("freeze_on_screen_off")
    val freezeOnScreenOff: Boolean = true
)

data class ExemptConfig(
    @SerializedName("exempt_foreground_services")
    val exemptForegroundServices: Boolean = true,
    @SerializedName("exempt_audio_playback")
    val exemptAudioPlayback: Boolean = true,
    @SerializedName("exempt_camera_microphone")
    val exemptCameraMicrophone: Boolean = true,
    @SerializedName("exempt_overlay_windows")
    val exemptOverlayWindows: Boolean = true
)

data class AppPolicyPayload(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int,
    val policy: Int // Corresponds to AppPolicy enum
)

// --- 通用键 ---
data class AppInstanceKey(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int
)