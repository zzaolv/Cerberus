// app/src/main/java/com/crfzit/crfzit/data/model/AppModels.kt
package com.crfzit.crfzit.data.model

import android.graphics.drawable.Drawable

enum class Policy {
    EXEMPTED, IMPORTANT, STANDARD, STRICT
}

data class AppInfo(
    val packageName: String,
    val appName: String,
    var policy: Policy,
    val icon: Drawable? = null,
    val isSystemApp: Boolean = false,
    var forcePlaybackExemption: Boolean = false,
    var forceNetworkExemption: Boolean = false
)

// [重构] 与C++后端完全同步的日志事件类型
enum class LogEventType {
    GENERIC_INFO,
    GENERIC_SUCCESS,
    GENERIC_WARNING,
    GENERIC_ERROR,
    DAEMON_START,
    DAEMON_SHUTDOWN,
    SCREEN_ON,
    SCREEN_OFF,
    APP_START,
    APP_STOP,
    APP_FOREGROUND,
    APP_BACKGROUND,
    APP_FROZEN,
    APP_UNFROZEN,
    POWER_UPDATE,
    POWER_WARNING,
    DOZE_STATE_CHANGE,
    DOZE_RESOURCE_REPORT,
    BATCH_OPERATION_START,
    NETWORK_BLOCKED,
    NETWORK_UNBLOCKED,
    SCHEDULED_TASK_EXEC,
    HEALTH_CHECK_STATUS, // [新增] 用于健康检查的特定类型
    UNKNOWN
}


data class LogEntry(
    val timestamp: Long,
    val eventType: LogEventType,
    val payload: Map<String, Any>
)