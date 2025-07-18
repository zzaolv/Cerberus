// app/src/main/java/com/crfzit/crfzit/data/repository/AppInfoRepository.kt
package com.crfzit.crfzit.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.crfzit.crfzit.data.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 单例仓库，负责加载并缓存所有已安装应用的基本信息。
 * [BUGFIX #3] 增加动态获取单个应用信息并更新缓存的能力。
 */
class AppInfoRepository private constructor(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private val appInfoCache: ConcurrentHashMap<String, AppInfo> = ConcurrentHashMap()
    private val cacheMutex = Mutex() // 用于保护缓存的加载过程

    suspend fun getAllApps(forceRefresh: Boolean = false): List<AppInfo> {
        cacheMutex.withLock {
            if (appInfoCache.isNotEmpty() && !forceRefresh) {
                return appInfoCache.values.toList()
            }
            loadAllInstalledApps()
            return appInfoCache.values.toList()
        }
    }

    /**
     * [BUGFIX #3] 新增：动态获取单个应用信息。
     * 如果缓存中不存在，则从系统中查询并添加到缓存中。
     */
    suspend fun getAppInfo(packageName: String): AppInfo? {
        // 先快速检查缓存，无锁
        appInfoCache[packageName]?.let { return it }

        // 缓存未命中，加锁进行查询和更新
        return cacheMutex.withLock {
            // 双重检查，防止在等待锁的过程中其他协程已经加载了缓存
            appInfoCache[packageName]?.let { return@withLock it }

            val app = loadSingleApp(packageName)
            app?.let { appInfoCache[packageName] = it }
            app
        }
    }

    private suspend fun loadAllInstalledApps() {
        // 这个函数应在持有 cacheMutex 锁的情况下被调用
        withContext(Dispatchers.IO) {
            appInfoCache.clear()
            val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filterNotNull()
                .mapNotNull { appInfo ->
                    try {
                        createAppInfoFrom(appInfo)
                    } catch (e: Exception) {
                        null
                    }
                }
            
            appInfoCache.putAll(apps.associateBy { it.packageName })
        }
    }
    
    private suspend fun loadSingleApp(packageName: String): AppInfo? = withContext(Dispatchers.IO) {
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            createAppInfoFrom(appInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            null // 应用可能已被卸载
        }
    }
    
    private fun createAppInfoFrom(appInfo: ApplicationInfo): AppInfo {
         return AppInfo(
            packageName = appInfo.packageName,
            appName = appInfo.loadLabel(packageManager).toString(),
            icon = appInfo.loadIcon(packageManager),
            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: AppInfoRepository? = null

        fun getInstance(context: Context): AppInfoRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppInfoRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}