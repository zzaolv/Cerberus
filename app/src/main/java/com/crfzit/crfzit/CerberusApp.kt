// app/src/main/java/com/crfzit/crfzit/CerberusApp.kt
package com.crfzit.crfzit

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.request.CachePolicy
import com.crfzit.crfzit.coil.AppIcon
import com.crfzit.crfzit.coil.AppIconFetcher
import com.crfzit.crfzit.data.repository.DaemonRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * [内存优化] 让我们的Application类实现ImageLoaderFactory接口，
 * 以便为整个应用提供一个自定义配置的Coil ImageLoader实例。
 */
class CerberusApp : Application(), ImageLoaderFactory {

    // 创建一个贯穿整个App生命周期的协程作用域
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        // 在App启动时，就初始化（预热）DaemonRepository单例
        DaemonRepository.getInstance(applicationScope)
    }

    /**
     * [内存优化] 实现ImageLoaderFactory的核心方法。
     * 在这里我们构建一个包含了自定义AppIconFetcher的ImageLoader。
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // 添加我们的自定义Fetcher。Coil会按顺序检查组件。
            .components {
                add(AppIconFetcher.Factory())
            }
            // 开启内存缓存
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                // 配置内存缓存大小，例如，最大为可用内存的15%
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            // 开启磁盘缓存
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                // 配置磁盘缓存
                coil.disk.DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02) // 磁盘空间的2%
                    .build()
            }
            // 全局配置，所有请求默认都将交叉淡入效果
            .crossfade(true)
            .build()
    }
}