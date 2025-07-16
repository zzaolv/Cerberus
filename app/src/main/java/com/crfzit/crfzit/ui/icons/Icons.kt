// app/src/main/java/com/crfzit/crfzit/ui/icons/Icons.kt
package com.crfzit.crfzit.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

// 使用一个单例对象来组织我们手动导入的图标
object AppIcons {
    val Tune: ImageVector
        get() = materialIcon(name = "Filled.Tune") {
            materialPath {
                moveTo(3.0f, 17.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-2.0f)
                lineTo(3.0f, 17.0f)
                close()
                moveTo(3.0f, 5.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(10.0f)
                lineTo(13.0f, 5.0f)
                lineTo(3.0f, 5.0f)
                close()
                moveTo(13.0f, 21.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-8.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(2.0f)
                close()
                moveTo(7.0f, 9.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-4.0f)
                lineTo(3.0f, 9.0f)
                lineTo(7.0f, 9.0f)
                close()
                moveTo(21.0f, 13.0f)
                verticalLineToRelative(-2.0f)
                lineTo(11.0f, 11.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(10.0f)
                close()
                moveTo(15.0f, 9.0f)
                horizontalLineToRelative(6.0f)
                lineTo(21.0f, 7.0f)
                horizontalLineToRelative(-6.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
    val Style: ImageVector
        get() = materialIcon(name = "Filled.Style") {
            materialPath {
                moveTo(2.53f, 19.65f)
                lineToRelative(1.34f, -1.34f)
                curveTo(4.96f, 17.7f, 6.0f, 17.21f, 7.0f, 17.21f)
                curveToRelative(0.53f, 0.0f, 1.04f, 0.11f, 1.5f, 0.31f)
                lineTo(12.0f, 14.0f)
                lineTo(3.0f, 5.0f)
                verticalLineTo(2.0f)
                horizontalLineToRelative(3.0f)
                lineToRelative(1.5f, 1.5f)
                curveTo(8.66f, 2.34f, 10.27f, 2.0f, 12.0f, 2.0f)
                curveToRelative(2.21f, 0.0f, 4.0f, 1.79f, 4.0f, 4.0f)
                curveToRelative(0.0f, 1.19f, -0.52f, 2.25f, -1.33f, 3.0f)
                lineToRelative(2.45f, 2.45f)
                curveTo(17.63f, 11.23f, 18.0f, 10.64f, 18.0f, 10.0f)
                curveToRelative(0.0f, -0.12f, -0.01f, -0.23f, -0.02f, -0.34f)
                curveToRelative(0.42f, -0.23f, 0.81f, -0.53f, 1.15f, -0.89f)
                lineToRelative(1.34f, 1.34f)
                curveToRelative(-0.56f, 0.56f, -1.24f, 1.0f, -1.97f, 1.29f)
                curveToRelative(0.01f, 0.12f, 0.01f, 0.24f, 0.01f, 0.36f)
                curveToRelative(0.0f, 1.3f, -0.83f, 2.4f, -2.0f, 2.83f)
                verticalLineTo(23.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineTo(15.0f)
                horizontalLineToRelative(1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(9.41f)
                lineTo(7.91f, 14.5f)
                curveToRelative(-0.2f, 0.45f, -0.31f, 0.96f, -0.31f, 1.5f)
                curveToRelative(0.0f, 1.0f, 0.3f, 1.96f, 0.84f, 2.79f)
                lineToRelative(-1.34f, 1.34f)
                curveTo(6.01f, 19.06f, 5.76f, 18.15f, 5.76f, 17.21f)
                curveToRelative(0.0f, -1.08f, 0.33f, -2.09f, 0.89f, -2.93f)
                lineTo(2.53f, 10.15f)
                curveToRelative(-0.56f, 0.84f, -0.89f, 1.85f, -0.89f, 2.93f)
                curveToRelative(0.0f, 0.94f, 0.25f, 1.85f, 0.71f, 2.65f)
                lineTo(2.53f, 19.65f)
                close()
                moveTo(12.0f, 8.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                close()
            }
        }
}