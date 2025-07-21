// app/src/main/java/com/crfzit/crfzit/data/model/IPCModels.kt
package com.crfzit.crfzit.data.model

import com.google.gson.annotations.SerializedName

// --- 通用消息结构 (保持不变) ---
data class CerberusMessage<T>(
    @SerializedName("v")
    val version: Int = 12,
    val type: String,
    @SerializedName("req_id")
    val requestId: String? = null,
    val payload: T
)

// --- UI <-> Daemon 模型 (保持不变) ---
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

// --- [移除] Probe -> Daemon 指令模型，因为决策已移至Daemon ---
// data class ProbeFreezePayload(...)
// data class ProbeUnfreezePayload(...)

// --- [新增] Probe -> Daemon 事件模型 ---
data class AppStateEventPayload(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int
)

// --- 配置模型 (UI -> Daemon -> Probe) (保持不变) ---
data class FullConfigPayload(
    @SerializedName("master_config")
    val masterConfig: MasterConfig,
    @SerializedName("exempt_config")
    val exemptConfig: ExemptConfig,
    @SerializedName("policies")
    val policies: List<AppPolicyPayload>,
    // [新增] Daemon会附加此列表给Probe
    @SerializedName("frozen_uids") 
    val frozenUids: List<Int>? = null 
)

data class MasterConfig(
    @SerializedName("is_enabled")
    val isEnabled: Boolean = true,
    @SerializedName("freeze_on_screen_off")
    val freezeOnScreenOff: Boolean = true
)

data class ExemptConfig(
    // [简化] 暂时只保留一个总开关
    @SerializedName("exempt_foreground_services")
    val exemptForegroundServices: Boolean = true
)

data class AppPolicyPayload(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int,
    val policy: Int // Corresponds to AppPolicy enum
)

// --- 通用键 (保持不变) ---
data class AppInstanceKey(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("user_id")
    val userId: Int
)