// D:/project/Cerberus/settings.gradle.kts

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// 核心：这里是唯一且合并后的 dependencyResolutionManagement 块
dependencyResolutionManagement {
    // 仓库管理模式设置，保持不变
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    // 🇨🇳 把所有仓库都放在这一个 repositories 块里
    repositories {
        google()
        mavenCentral()
        // 🇨🇳 把 Xposed 的仓库也加到这里
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "CRFzit" // 您可以修改为 "Cerberus"
include(":app")