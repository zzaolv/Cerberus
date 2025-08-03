// app/src/main/java/com/crfzit/crfzit/data/model/AppModels.kt
package com.crfzit.crfzit.data.model

enum class Policy(val value: Int) {
    EXEMPTED(0),
    IMPORTANT(1),
    STANDARD(2),
    STRICT(3);

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
    var forceNetworkExemption: Boolean = false
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
    val cpuUsagePercent: Float,
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