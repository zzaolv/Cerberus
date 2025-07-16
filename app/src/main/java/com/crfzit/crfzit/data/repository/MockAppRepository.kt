// app/src/main/java/com/crfzit/crfzit/data/repository/MockAppRepository.kt
package com.crfzit.crfzit.data.repository

import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.LogEntry
import com.crfzit.crfzit.data.model.LogLevel
import com.crfzit.crfzit.data.model.Policy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class MockAppRepository {
    // 【修改】修正所有 AppInfo 的实例化
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

    fun getLogsStream(): Flow<List<LogEntry>> = flow {
        val logs = listOf(
            LogEntry(System.currentTimeMillis() - 1000, LogLevel.EVENT, "屏幕已关闭，进入后台计时。", null),
            LogEntry(System.currentTimeMillis() - 5000, LogLevel.SUCCESS, "已将 [拼多多] 置于cgroup冻结状态 (原因: 后台超时)", "拼多多"),
            LogEntry(System.currentTimeMillis() - 15000, LogLevel.INFO, "为 [微信] 解除冻结 (原因: 收到高优先级推送)", "微信"),
            LogEntry(System.currentTimeMillis() - 25000, LogLevel.WARNING, "SELinux 策略加载成功，有 1 条未使用规则。", null),
            LogEntry(System.currentTimeMillis() - 35000, LogLevel.ERROR, "连接探针失败，请检查 LSPosed 模块是否激活。", null),
            LogEntry(System.currentTimeMillis() - 45000, LogLevel.EVENT, "守护进程启动成功 (PID: 12345)", null)
        )
        emit(logs)
    }
}