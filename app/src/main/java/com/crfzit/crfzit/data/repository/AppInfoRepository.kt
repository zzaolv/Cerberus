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
 * [MEM_OPT] 仓库的重大重构。
 * 1. 它不再预加载所有应用信息。getAllApps被移除。
 * 2. 缓存中的AppInfo对象不再持有Drawable，而是持有ApplicationInfo。
 * 3. 它的唯一职责是：根据包名，按需提供应用的元数据。
 */
class AppInfoRepository private constructor(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    // 缓存只使用包名作为Key，因为此仓库只处理主用户的应用元数据
    private val appInfoCache: ConcurrentHashMap<String, AppInfo> = ConcurrentHashMap()
    private val cacheMutex = Mutex()

    /**
     * [MEM_OPT] 移除了 getAllApps 方法。
     * ViewModel不应该再要求一次性获取所有应用信息。
     */
    // suspend fun getAllApps(...) { ... }

    /**
     * [MEM_OPT] 这是现在获取应用信息的唯一入口。
     * 它会按需加载并缓存AppInfo（不含Drawable）。
     */
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
            // [MEM_OPT] 将重量级的Drawable替换为轻量级的ApplicationInfo对象
            applicationInfo = appInfo,
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