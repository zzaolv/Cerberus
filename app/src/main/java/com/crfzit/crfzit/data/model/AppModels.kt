// app/src/main/java/com/crfzit/crfzit/data/model/AppModels.kt
package com.crfzit.crfzit.data.model

// [核心修改] 将 displayName 添加到核心数据模型中，并为所有成员提供
enum class Policy(val value: Int, val displayName: String) {
    EXEMPTED(0, "豁免"),
    IMPORTANT(1, "重要"), // 即使UI不用，也为它提供一个合理的名称
    STANDARD(2, "智能"),
    STRICT(3, "严格");

    companion object {
        fun fromInt(value: Int) = entries.find { it.value == value } ?: EXEMPTED
    }
}

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val userId: Int = 0,

    var policy: Policy = Policy.EXEMPTED,
    var forcePlaybackExemption: Boolean = false,
    var forceNetworkExemption: Boolean = false,
    var forceLocationExemption: Boolean = false,
    var allowTimedUnfreeze: Boolean = true
)

enum class LogLevel(val value: Int) {
    INFO(0),
    SUCCESS(1),
    WARN(2),
    ERROR(3),
    EVENT(4),
    DOZE(5),
    BATTERY(6),
    REPORT(7),
    ACTION_OPEN(8),
    ACTION_CLOSE(9),
    ACTION_FREEZE(10),
    ACTION_UNFREEZE(11),
    ACTION_DELAY(12),
    TIMER(13),
    BATCH_PARENT(14);

    companion object {
        fun fromInt(value: Int) = entries.find { it.value == value } ?: INFO
    }
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val category: String,
    val message: String,
    val packageName: String?,
    val userId: Int = -1
)

data class MetricsRecord(
    val timestamp: Long,
    val totalCpuUsagePercent: Float,
    val perCoreCpuUsagePercent: List<Float>,
    val memTotalKb: Long,
    val memAvailableKb: Long,
    val swapTotalKb: Long,
    val swapFreeKb: Long,
    val batteryLevel: Int,
    val batteryTempCelsius: Float,
    val batteryPowerWatt: Float,
    val isCharging: Boolean,
    val isScreenOn: Boolean,
    val isAudioPlaying: Boolean,
    val isLocationActive: Boolean
)