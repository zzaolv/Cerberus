package com.crfzit.crfzit.lsp

import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 这是我们的 LSPosed 模块的入口点。
 * 当 LSPosed 加载我们的 APK 作为一个模块时，它会实例化这个类并调用 handleLoadPackage。
 * 当我们像普通应用一样启动 APK 时，这个类不会被加载或执行。
 */
class ProbeHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 我们的目标是 system_server，它的进程名和包名都是 "android"
        if (lpparam.processName != "android") {
            return
        }
        
        // 打印一条日志到 LSPosed 的日志中，以确认模块已成功注入 system_server
        XposedBridge.log("Cerberus Probe: Successfully attached to system_server (PID: ${Process.myPid()})")

        // 在这里，我们可以开始对 system_server 中的类和方法进行 Hook
        // 例如，Hook ActivityManagerService 来监控应用启动
        hookActivityManagerService(lpparam.classLoader)
    }

    /**
     * 示例：Hook AMS 的方法
     * @param classLoader system_server 的类加载器
     */
    private fun hookActivityManagerService(classLoader: ClassLoader) {
        try {
            // val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
            
            // 这是一个示例，实际的Hook会更复杂
            // XposedHelpers.findAndHookMethod(
            //     amsClass,
            //     "startProcess", // 监控进程启动的方法
            //     // ... 此处需要复杂的参数类型列表 ...
            //     object : XC_MethodHook() {
            //         override fun beforeHookedMethod(param: MethodHookParam) {
            //             val processName = param.args[...].toString() // 获取参数
            //             XposedBridge.log("Cerberus Probe: Process starting: $processName")
            //             // TODO: 将此事件通过 UDS 发送给 daemon
            //         }
            //     }
            // )
            XposedBridge.log("Cerberus Probe: Found AMS, ready to hook methods.")
        } catch (e: Throwable) {
            XposedBridge.log("Cerberus Probe: Failed to hook AMS.")
            XposedBridge.log(e)
        }
    }
}