// app/src/main/java/com/crfzit/crfzit/data/model/AppModels.kt
package com.crfzit.crfzit.data.model

import android.graphics.drawable.Drawable

// 应用策略等级
enum class Policy {
    EXEMPTED, // 自由后台
    IMPORTANT, // 重要
    STANDARD, // 智能
    STRICT, // 严格
}

// 应用信息，【已合并】用于配置列表和UI显示
data class AppInfo(
    val packageName: String,
    val appName: String,
    val policy: Policy,
    val icon: Drawable? = null, // 【新增】用于UI显示，可为空
    val isSystemApp: Boolean = false,
    val forcePlaybackExemption: Boolean = false,
    val forceNetworkExemption: Boolean = false
)
//--- 日志页模型 ---
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
    // [新增] 电源与Doze事件
    POWER_UPDATE,
    POWER_WARNING,
    DOZE_STATE_CHANGE,
    DOZE_RESOURCE_REPORT,
    // [新增] 批量操作与网络控制事件
    BATCH_OPERATION_START,
    NETWORK_BLOCKED,
    NETWORK_UNBLOCKED,
    // [新增] 定时任务事件
    SCHEDULED_TASK_EXEC,
    // 对于无法识别的类型
    UNKNOWN
}

data class LogEntry(
    val timestamp: Long,
    val eventType: LogEventType,
    val payload: Map<String, Any>
)