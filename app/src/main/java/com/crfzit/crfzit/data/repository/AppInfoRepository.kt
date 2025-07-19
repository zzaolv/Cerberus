// app/src/main/java/com/crfzit/crfzit/data/repository/AppInfoRepository.kt
package com.crfzit.crfzit.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserManager
import com.crfzit.crfzit.data.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class AppInfoRepository private constructor(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    // [FIX #2] 缓存的Key需要包含userId，以区分不同用户的同一个应用
    private val appInfoCache: ConcurrentHashMap<Pair<String, Int>, AppInfo> = ConcurrentHashMap()
    private val cacheMutex = Mutex()

    suspend fun getAllApps(forceRefresh: Boolean = false): List<AppInfo> {
        cacheMutex.withLock {
            if (appInfoCache.isNotEmpty() && !forceRefresh) {
                return appInfoCache.values.toList()
            }
            loadAllInstalledAppsForAllUsers()
            return appInfoCache.values.toList()
        }
    }

    suspend fun getAppInfo(packageName: String, userId: Int): AppInfo? {
        val key = packageName to userId
        appInfoCache[key]?.let { return it }

        return cacheMutex.withLock {
            appInfoCache[key]?.let { return@withLock it }
            
            // [FIX #2] 需要为指定用户加载应用信息
            val app = loadSingleAppForUser(packageName, userId)
            app?.let { appInfoCache[key] = it }
            app
        }
    }
    
    // [FIX #2] 重写加载逻辑，使其遍历所有用户
    private suspend fun loadAllInstalledAppsForAllUsers() {
        withContext(Dispatchers.IO) {
            appInfoCache.clear()
            val userManager = context.getSystemService(UserManager::class.java)
            val userHandles = userManager.userProfiles

            userHandles.forEach { userHandle ->
                val userId = userManager.getSerialNumberForUser(userHandle).toInt()
                // 安卓内部API，用以获取指定用户的Context，从而获取其PackageManager
                val userContext = context.createPackageContextAsUser(
                    context.packageName, 0, userHandle
                )
                val userPackageManager = userContext.packageManager

                val appsForUser = userPackageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filterNotNull()
                    .mapNotNull { appInfo ->
                        try {
                            // 使用userPackageManager来加载应用名和图标
                            createAppInfoFrom(appInfo, userPackageManager, userId)
                        } catch (e: Exception) {
                            null
                        }
                    }
                
                appInfoCache.putAll(appsForUser.associateBy { it.packageName to it.userId })
            }
        }
    }
    
    private suspend fun loadSingleAppForUser(packageName: String, userId: Int): AppInfo? = withContext(Dispatchers.IO) {
        try {
            val userManager = context.getSystemService(UserManager::class.java)
            val userHandle = userManager.getUserForSerialNumber(userId.toLong()) ?: return@withContext null
            
            val userContext = context.createPackageContextAsUser(context.packageName, 0, userHandle)
            val userPackageManager = userContext.packageManager

            val appInfo = userPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            createAppInfoFrom(appInfo, userPackageManager, userId)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            // IllegalArgumentException if user does not exist
            null
        }
    }
    
    private fun createAppInfoFrom(
        appInfo: ApplicationInfo, 
        pm: PackageManager, // 传入对应的PackageManager
        userId: Int
    ): AppInfo {
         return AppInfo(
            packageName = appInfo.packageName,
            appName = appInfo.loadLabel(pm).toString(),
            icon = appInfo.loadIcon(pm),
            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            userId = userId // 明确设置userId
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