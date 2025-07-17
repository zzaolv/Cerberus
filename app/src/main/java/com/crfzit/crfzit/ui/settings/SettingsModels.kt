// app/src/main/java/com/crfzit/crfzit/ui/settings/SettingsModels.kt
package com.crfzit.crfzit.ui.settings

// [修复] 更新枚举定义以匹配C++后端
enum class FreezerType(val displayName: String) {
    AUTO("自动选择"),
    CGROUP_V2("cgroup v2"),
    CGROUP_V1("cgroup v1"),
    // 修改了名称，但显示名和顺序保持不变
    FREEZER_SIGSTOP("SIGSTOP") 
}

data class SettingsState(
    val freezerType: FreezerType = FreezerType.AUTO,
    val unfreezeIntervalMinutes: Int = 0
)

data class HealthCheckState(
    val daemonPid: Int = -1,
    val isProbeConnected: Boolean = false,
    val isLoading: Boolean = true
)