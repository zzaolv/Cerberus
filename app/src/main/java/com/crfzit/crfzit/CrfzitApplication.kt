// app/src/main/java/com/crfzit/crfzit/CrfzitApplication.kt
package com.crfzit.crfzit

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.crfzit.crfzit.util.ApplicationInfoFetcher
import com.crfzit.crfzit.util.ApplicationInfoKeyer

class CrfzitApplication : Application(), ImageLoaderFactory {

    /**
     * 实现ImageLoaderFactory接口，为整个应用提供一个自定义配置的Coil ImageLoader实例。
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // [OPT] 配置内存缓存
            .memoryCache {
                MemoryCache.Builder(this)
                    // 使用应用可用内存的15%作为内存缓存，这是一个比较合理的值
                    .maxSizePercent(0.15)
                    .build()
            }
            // [OPT] 配置磁盘缓存
            .diskCache {
                DiskCache.Builder()
                    // 指定缓存目录为应用内部缓存目录下的 image_cache 文件夹
                    .directory(this.cacheDir.resolve("image_cache"))
                    // 设置磁盘缓存最大为 50MB
                    .maxSizeBytes(50 * 1024 * 1024)
                    .build()
            }
            .components {
                add(ApplicationInfoKeyer())
                add(ApplicationInfoFetcher.Factory(this@CrfzitApplication))
            }
            .respectCacheHeaders(false)
            .build()
    }
}