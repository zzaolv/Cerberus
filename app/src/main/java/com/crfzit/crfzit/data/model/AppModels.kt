// app/src/main/java/com/crfzit/crfzit/data/model/AppModels.kt
package com.crfzit.crfzit.data.model

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable

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
    // [MEM_OPT] 不再直接持有Drawable对象，这是内存优化的核心。
    // 我们持有轻量级的ApplicationInfo，让Coil按需加载图标。
    val applicationInfo: ApplicationInfo?,
    val isSystemApp: Boolean,
    val userId: Int = 0,

    var policy: Policy = Policy.EXEMPTED,
    var forcePlaybackExemption: Boolean = false,
    var forceNetworkExemption: Boolean = false
)

enum class LogLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
    EVENT
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val message: String,
    val appName: String? = null
)