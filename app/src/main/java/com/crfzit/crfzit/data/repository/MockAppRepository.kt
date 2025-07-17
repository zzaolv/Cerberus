// app/src/main/java/com/crfzit/crfzit/data/repository/MockAppRepository.kt
package com.crfzit.crfzit.data.repository

import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.LogEntry
import com.crfzit.crfzit.data.model.LogEventType // 【修复】导入新的 LogEventType
import com.crfzit.crfzit.data.model.Policy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class MockAppRepository {
    private val mockApps = mutableListOf(
        AppInfo("com.tencent.mm", "微信", Policy.IMPORTANT),
        AppInfo("com.eg.android.AlipayGphone", "支付宝", Policy.IMPORTANT),
        AppInfo("com.alibaba.android.rimet", "钉钉", Policy.IMPORTANT),
        AppInfo("com.coolapk.market", "酷安", Policy.STANDARD),
        AppInfo("com.bilibili.app.in", "哔哩哔哩", Policy.STANDARD),
        AppInfo("com.xunmeng.pinduoduo", "拼多多", Policy.STRICT),
        AppInfo("com.android.systemui", "系统界面", Policy.EXEMPTED, isSystemApp = true),
        AppInfo("com.android.settings", "设置", Policy.EXEMPTED, isSystemApp = true)
    )

    suspend fun getAllApps(): List<AppInfo> {
        delay(500) // 模拟网络延迟
        return mockApps
    }

    suspend fun setPolicyForApp(packageName: String, newPolicy: Policy) {
        delay(100) // 模拟设置延迟
        val index = mockApps.indexOfFirst { it.packageName == packageName }
        if (index != -1) {
            mockApps[index] = mockApps[index].copy(policy = newPolicy)
        }
    }

    // 【核心修复】完全重写此函数以使用新的结构化日志模型
    fun getLogsStream(): Flow<List<LogEntry>> = flow {
        val now = System.currentTimeMillis()
        val logs = listOf(
            LogEntry(
                now - 1000,
                LogEventType.SCREEN_OFF,
                mapOf("message" to "屏幕已关闭，进入后台计时。")
            ),
            LogEntry(
                now - 5000,
                LogEventType.APP_FROZEN,
                mapOf(
                    "app_name" to "拼多多",
                    "package_name" to "com.xunmeng.pinduoduo",
                    "reason" to "后台超时",
                    "pid_count" to 2,
                    "session_duration_s" to 180,
                    "cumulative_duration_s" to 3600
                )
            ),
            LogEntry(
                now - 15000,
                LogEventType.APP_UNFROZEN,
                mapOf(
                    "app_name" to "微信",
                    "package_name" to "com.tencent.mm",
                    "reason" to "收到高优先级推送"
                )
            ),
            LogEntry(
                now - 25000,
                LogEventType.GENERIC_WARNING,
                mapOf("message" to "SELinux 策略加载成功，有 1 条未使用规则。")
            ),
            LogEntry(
                now - 35000,
                LogEventType.GENERIC_ERROR,
                mapOf("message" to "连接探针失败，请检查 LSPosed 模块是否激活。")
            ),
            LogEntry(
                now - 45000,
                LogEventType.DAEMON_START,
                mapOf("message" to "守护进程启动成功 (PID: 12345)")
            ),
            LogEntry(
                now - 55000,
                LogEventType.APP_FOREGROUND,
                mapOf(
                    "app_name" to "微信",
                    "package_name" to "com.tencent.mm",
                    "reason" to "App became foreground"
                )
            )
        )
        emit(logs)
    }
}