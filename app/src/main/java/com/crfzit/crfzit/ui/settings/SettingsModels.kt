// app/src/main/java/com/crfzit/crfzit/ui/settings/SettingsModels.kt
package com.crfzit.crfzit.ui.settings

// 与 C++ 后端同步的 FreezerType 枚举
enum class FreezerType(val displayName: String) {
    AUTO("自动选择"),
    CGROUP_V2("cgroup v2"),
    CGROUP_V1("cgroup v1"),
    SIGSTOP("SIGSTOP")
}

data class SettingsState(
    val freezerType: FreezerType = FreezerType.AUTO,
    val unfreezeIntervalMinutes: Int = 0 // 0 表示禁用
)

data class HealthCheckState(
    val daemonPid: Int = -1,
    val isProbeConnected: Boolean = false,
    val isLoading: Boolean = true
)