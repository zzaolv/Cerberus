// app/src/main/java/com/crfzit/crfzit/data/model/AppModels.kt
package com.crfzit.crfzit.data.model

import android.graphics.drawable.Drawable

//--- 配置页模型 ---

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

// 【重构】日志事件类型，与C++后端保持一致
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
    // 对于无法识别的类型
    UNKNOWN
}


// 【重构】新的日志条目数据类
data class LogEntry(
    val timestamp: Long,
    val eventType: LogEventType,
    val payload: Map<String, Any> // 使用Map来接收灵活的JSON payload
)