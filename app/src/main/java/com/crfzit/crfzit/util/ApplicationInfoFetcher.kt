// app/src/main/java/com/crfzit/crfzit/util/ApplicationInfoFetcher.kt
package com.crfzit.crfzit.util

import android.content.Context
import android.content.pm.ApplicationInfo
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options

/**
 * 一个自定义的Coil Fetcher，用于从ApplicationInfo对象加载应用程序图标。
 */
class ApplicationInfoFetcher(
    private val options: Options,
    private val data: ApplicationInfo
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        // 使用PackageManager从ApplicationInfo加载图标。这是一个同步调用，但Coil会在后台线程执行它。
        val drawable = options.context.packageManager.getApplicationIcon(data)

        // isSampled为false表示我们返回的是原始大小的Drawable
        return DrawableResult(
            drawable = drawable,
            isSampled = false,
            dataSource = DataSource.DISK // 图标来自设备磁盘，所以标记为DISK
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<ApplicationInfo> {
        override fun create(data: ApplicationInfo, options: Options, imageLoader: ImageLoader): Fetcher {
            return ApplicationInfoFetcher(options, data)
        }
    }
}

/**
 * 一个自定义的Coil Keyer，用于为ApplicationInfo生成一个稳定的缓存键。
 */
class ApplicationInfoKeyer : Keyer<ApplicationInfo> {
    override fun key(data: ApplicationInfo, options: Options): String {
        // [FIX] 编译错误最终、最稳妥的修复：
        // 鉴于您的构建环境无法解析ApplicationInfo的任何版本号字段/方法，
        // 我们采取最安全的策略：仅使用包名作为缓存键。
        // 这将100%解决编译问题。
        // 副作用：如果一个应用更新了图标但包名未变，Coil可能不会立即加载新图标，
        // 而是显示缓存的旧图标，直到缓存过期。这在大多数情况下是可以接受的。
        return data.packageName
    }
}