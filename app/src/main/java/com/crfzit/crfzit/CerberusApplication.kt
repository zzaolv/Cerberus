// app/src/main/java/com/crfzit/crfzit/CerberusApplication.kt
package com.crfzit.crfzit

import android.app.Application
import com.crfzit.crfzit.data.repository.AppInfoRepository
import com.crfzit.crfzit.data.uds.UdsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CerberusApplication : Application() {

    // 创建一个贯穿整个应用生命周期的协程作用域
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 1. 在应用启动时，就初始化（预热）UDS客户端单例
        // 它会自动开始连接，并在后台保持
        UdsClient.getInstance(applicationScope)

        // 2. 异步加载所有应用信息到缓存
        applicationScope.launch {
            AppInfoRepository.getInstance(this@CerberusApplication).loadAllInstalledApps()
        }
    }
}