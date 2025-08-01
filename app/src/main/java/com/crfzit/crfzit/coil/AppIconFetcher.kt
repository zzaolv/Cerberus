// app/src/main/java/com/crfzit/crfzit/coil/AppIconFetcher.kt
package com.crfzit.crfzit.coil

import android.content.pm.PackageManager
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options

/**
 * [内存优化] 自定义数据类，用于向Coil清晰地表达“我想要加载一个应用图标”的意图。
 * @param packageName 要加载图标的应用包名。
 */
data class AppIcon(val packageName: String)

/**
 * [内存优化] Coil的自定义Fetcher，它知道如何处理 AppIcon 数据类。
 * 当Coil看到一个类型为AppIcon的请求时，它会使用这个Fetcher来执行加载逻辑。
 */
class AppIconFetcher(
    private val options: Options,
    private val data: AppIcon
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val context = options.context
        val packageManager = context.packageManager

        // 从PackageManager获取应用图标的Drawable
        val drawable = try {
            packageManager.getApplicationIcon(data.packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            // 如果应用不存在，返回null，Coil会使用error占位符
            null
        }

        // 必须返回一个DrawableResult
        return DrawableResult(
            drawable = drawable ?: packageManager.defaultActivityIcon, // 如果找不到，使用系统默认图标
            isSampled = false, // 我们没有对它进行采样
            dataSource = DataSource.DISK // 图标来自设备存储，所以是DISK
        )
    }

    /**
     * Factory是必需的，它告诉Coil在何时使用我们的AppIconFetcher。
     */
    class Factory : Fetcher.Factory<AppIcon> {
        override fun create(data: AppIcon, options: Options, imageLoader: ImageLoader): Fetcher {
            return AppIconFetcher(options, data)
        }
    }
}