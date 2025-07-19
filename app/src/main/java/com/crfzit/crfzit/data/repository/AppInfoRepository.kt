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
 * [核心修复] 此仓库的职责被重新定义。
 * 它不再是所有应用列表的来源，而是一个按需提供应用元数据（如名称、图标）的工具。
 * 尤其重要的是，它只查询当前用户（通常是User 0）的应用信息。
 */
class AppInfoRepository private constructor(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    // 缓存只使用包名作为Key，因为此仓库只处理主用户的应用元数据
    private val appInfoCache: ConcurrentHashMap<String, AppInfo> = ConcurrentHashMap()
    private val cacheMutex = Mutex()

    suspend fun getAllApps(forceRefresh: Boolean = false): List<AppInfo> {
        cacheMutex.withLock {
            if (appInfoCache.isNotEmpty() && !forceRefresh) {
                return appInfoCache.values.toList()
            }
            loadAllInstalledApps()
            return appInfoCache.values.toList()
        }
    }

    // [核心修复] getAppInfo不再需要userId。它只为给定的包名查找主用户的应用信息。
    suspend fun getAppInfo(packageName: String): AppInfo? {
        appInfoCache[packageName]?.let { return it }

        return cacheMutex.withLock {
            // 双重检查锁定
            appInfoCache[packageName]?.let { return@withLock it }

            val app = loadSingleApp(packageName)
            app?.let { appInfoCache[packageName] = it }
            app
        }
    }

    private suspend fun loadAllInstalledApps() {
        withContext(Dispatchers.IO) {
            appInfoCache.clear()
            // [核心修复] 只获取当前用户(user 0)的应用列表作为基础元数据
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
            // 如果分身应用在主空间不存在，这里会返回null，这是预期行为
            null
        }
    }

    private fun createAppInfoFrom(appInfo: ApplicationInfo): AppInfo {
        return AppInfo(
            packageName = appInfo.packageName,
            appName = appInfo.loadLabel(packageManager).toString(),
            icon = appInfo.loadIcon(packageManager),
            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            userId = 0 // 这个仓库获取的都是主用户空间的应用，因此userId固定为0
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