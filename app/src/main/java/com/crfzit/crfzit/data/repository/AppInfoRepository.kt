// app/src/main/java/com/crfzit/crfzit/data/repository/AppInfoRepository.kt
package com.crfzit.crfzit.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.crfzit.crfzit.data.model.AppInfo
import com.crfzit.crfzit.data.model.Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// 【核心修复】将 AppInfoRepository 变成一个单例，以实现全局缓存
class AppInfoRepository private constructor(context: Context) {

    private val packageManager: PackageManager = context.packageManager
    // 使用 ConcurrentHashMap 确保线程安全
    private val appInfoCache: MutableMap<String, AppInfo> = ConcurrentHashMap()

    // 对外提供获取已缓存数据的方法
    fun getCachedApps(): Map<String, AppInfo> {
        return appInfoCache.toMap()
    }

    // 加载函数现在只负责填充缓存
    suspend fun loadAllInstalledApps(forceRefresh: Boolean = false) {
        if (appInfoCache.isNotEmpty() && !forceRefresh) {
            return
        }

        withContext(Dispatchers.IO) {
            val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filterNotNull()
                .mapNotNull { appInfo ->
                    try {
                        AppInfo(
                            packageName = appInfo.packageName,
                            appName = appInfo.loadLabel(packageManager).toString(),
                            icon = appInfo.loadIcon(packageManager),
                            // 默认策略现在是 EXEMPTED，与后端逻辑一致
                            policy = Policy.EXEMPTED,
                            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                .associateBy { it.packageName }

            // 填充缓存
            appInfoCache.clear()
            appInfoCache.putAll(apps)
        }
    }

    // 单例模式的实现
    companion object {
        @Volatile
        private var INSTANCE: AppInfoRepository? = null

        fun getInstance(context: Context): AppInfoRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = AppInfoRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}