// app/src/main/java/com/crfzit/crfzit/CerberusApp.kt
package com.crfzit.crfzit

import android.app.Application
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CerberusApp : Application() {

    // 创建一个贯穿整个App生命周期的协程作用域
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        // 在App启动时，就初始化（预热）DaemonRepository单例
        DaemonRepository.getInstance(applicationScope)
    }
}