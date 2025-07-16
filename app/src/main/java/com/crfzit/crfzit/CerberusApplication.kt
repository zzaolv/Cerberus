// app/src/main/java/com/crfzit/crfzit/CerberusApplication.kt
package com.crfzit.crfzit

import android.app.Application
import com.crfzit.crfzit.data.uds.UdsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// 【核心修复】创建自定义 Application 类
class CerberusApplication : Application() {

    // 创建一个贯穿整个应用生命周期的协程作用域
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 在应用启动时，就初始化（预热）UDS客户端单例
        UdsClient.getInstance(applicationScope)
    }
}