package com.crfzit.crfzit.data.model

//--- 配置页模型 ---

// 应用策略等级
enum class Policy {
    EXEMPTED, // 自由后台
    IMPORTANT, // 重要
    STANDARD, // 智能
    STRICT, // 严格
}

// 应用信息，用于配置列表
data class AppInfo(
    val packageName: String,
    val appName: String,
    val policy: Policy,
    val isSystemApp: Boolean = false,
    val forcePlaybackExemption: Boolean = false,
    val forceNetworkExemption: Boolean = false // <-- 确保这里没有多余的逗号
)

//--- 日志页模型 ---

// 日志级别
enum class LogLevel {
    INFO, // 信息
    SUCCESS, // 成功
    WARNING, // 警告
    ERROR, // 错误
    EVENT // 事件
}

// 单条日志条目
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val message: String,
    val appName: String? = null
)