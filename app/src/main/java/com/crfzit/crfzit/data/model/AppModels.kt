// app/src/main/java/com/crfzit/crfzit/data/model/AppModels.kt
package com.crfzit.crfzit.data.model

import android.graphics.drawable.Drawable

/**
 * 应用策略等级枚举，值必须与守护进程中的 AppPolicy 枚举完全对应。
 * EXEMPTED = 0, IMPORTANT = 1, STANDARD = 2, STRICT = 3
 */
enum class Policy(val value: Int) {
    EXEMPTED(0),
    IMPORTANT(1),
    STANDARD(2),
    STRICT(3);

    companion object {
        fun fromInt(value: Int) = entries.find { it.value == value } ?: EXEMPTED
    }
}

/**
 * 用于配置页和UI显示的聚合应用信息模型。
 */
data class AppInfo(
    // 从 PackageManager 获取
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean,
    
    // 从守护进程获取并合并
    var policy: Policy = Policy.EXEMPTED, // 默认为豁免，符合用户自选原则
    var forcePlaybackExemption: Boolean = false,
    var forceNetworkExemption: Boolean = false
)

/**
 * 日志级别枚举
 */
enum class LogLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
    EVENT 
}

/**
 * 单条日志条目模型
 */
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val message: String,
    val appName: String? = null // 可选，关联的应用名
)