// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    // 正确引用在 toml 中定义的 compose 插件
    alias(libs.plugins.jetbrains.compose) apply false
}