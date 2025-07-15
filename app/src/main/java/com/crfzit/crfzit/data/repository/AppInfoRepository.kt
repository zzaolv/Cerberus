// app/src/main/java/com/crfzit/crfzit/data/repository/AppInfoRepository.kt
package com.crfzit.crfzit.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.Policy // 【新增】导入Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppInfoRepository(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    // 【修改】缓存类型更新为新的 AppInfo
    private var appInfoCache: Map<String, AppInfo>? = null

    suspend fun getAllInstalledApps(forceRefresh: Boolean = false): Map<String, AppInfo> {
        // 使用缓存，避免每次都重复加载
        if (appInfoCache != null && !forceRefresh) {
            return appInfoCache!!
        }
        
        return withContext(Dispatchers.IO) {
            val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filterNotNull()
                .mapNotNull { appInfo ->
                    // 尝试加载应用名和图标
                    try {
                        // 【修改】创建统一的 AppInfo 对象
                        // 对于仓库来说，它只负责提供基础信息，策略等配置信息留空或使用默认值
                        AppInfo(
                            packageName = appInfo.packageName,
                            appName = appInfo.loadLabel(packageManager).toString(),
                            icon = appInfo.loadIcon(packageManager),
                            policy = Policy.STANDARD, // 提供一个默认值
                            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    } catch (e: Exception) {
                        // 如果某个应用信息加载失败，则跳过
                        null
                    }
                }
                .associateBy { it.packageName } // 转换成以包名为key的Map，方便快速查找
            
            appInfoCache = apps
            apps
        }
    }
}