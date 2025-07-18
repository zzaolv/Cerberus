// app/src/main/java/com/crfzit/crfzit/data/model/AppModels.kt
package com.crfzit.crfzit.data.model

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
    val icon: Drawable?,
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