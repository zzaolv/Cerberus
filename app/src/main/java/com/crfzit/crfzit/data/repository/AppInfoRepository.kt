// app/src/main/java/com/crfzit/crfzit/data/repository/AppInfoRepository.kt
package com.crfzit.crfzit.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.crfzit.crfzit.data.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 单例仓库，负责加载并缓存所有已安装应用的基本信息（包名、应用名、图标、是否系统应用）。
 * 这些信息相对静态，只需加载一次。应用的动态策略由其他仓库负责。
 */
class AppInfoRepository private constructor(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private val appInfoCache: MutableMap<String, AppInfo> = ConcurrentHashMap()

    suspend fun getAllApps(forceRefresh: Boolean = false): List<AppInfo> {
        if (appInfoCache.isNotEmpty() && !forceRefresh) {
            return appInfoCache.values.toList()
        }
        
        loadAllInstalledApps()
        return appInfoCache.values.toList()
    }

    private suspend fun loadAllInstalledApps() {
        withContext(Dispatchers.IO) {
            appInfoCache.clear()
            val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filterNotNull()
                .mapNotNull { appInfo ->
                    try {
                        AppInfo(
                            packageName = appInfo.packageName,
                            appName = appInfo.loadLabel(packageManager).toString(),
                            icon = appInfo.loadIcon(packageManager),
                            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    } catch (e: Exception) {
                        // 某些应用（如卸载残留）可能无法加载标签
                        null
                    }
                }
            
            appInfoCache.putAll(apps.associateBy { it.packageName })
        }
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