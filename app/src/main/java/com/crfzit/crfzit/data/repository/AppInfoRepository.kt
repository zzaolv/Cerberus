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
 * [内存优化] 此仓库现在是一个轻量级的元数据提供者。
 * 它缓存的AppInfo对象不包含Drawable，因此缓存占用的内存非常小。
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
            null
        }
    }

    private fun createAppInfoFrom(appInfo: ApplicationInfo): AppInfo {
        return AppInfo(
            packageName = appInfo.packageName,
            appName = appInfo.loadLabel(packageManager).toString(),
            // [内存优化] 不再加载和存储Drawable对象
            // icon = appInfo.loadIcon(packageManager),
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