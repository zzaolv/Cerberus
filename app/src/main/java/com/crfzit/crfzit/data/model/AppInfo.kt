// app/src/main/java/com/crfzit/crfzit/data/model/AppInfo.kt
package com.crfzit.crfzit.data.model

import android.graphics.drawable.Drawable

/**
 * 用于在UI层缓存应用的静态显示信息
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable
)