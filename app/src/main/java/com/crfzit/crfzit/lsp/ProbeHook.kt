// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.content.ComponentName // 【新增】导入
import android.content.pm.ApplicationInfo // 【新增】导入
import android.os.Process
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 这是我们的 LSPosed 模块的入口点。
 */
class ProbeHook : IXposedHookLoadPackage {

    // 【新增】为 Probe 创建一个独立的协程作用域
    private val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val udsClient = UdsClient(probeScope)
    private val gson = Gson()

    companion object {
        private const val TAG = "CerberusProbe"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 我们的目标是 system_server，它的进程名和包名都是 "android"
        if (lpparam.packageName != "android" || lpparam.processName != "android") {
            return
        }
        
        // 模块注入成功后，立即启动UDS客户端尝试连接Daemon
        udsClient.start()

        XposedBridge.log("$TAG: Successfully attached to system_server (PID: ${Process.myPid()})")

        // 开始 Hook 关键系统服务
        hookActivityManagerService(lpparam.classLoader)
    }

    /**
     * Hook AMS 的方法来监控应用生命周期
     */
    private fun hookActivityManagerService(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)

            // Hook cleanUpRemovedTask(Task, boolean) - 任务被移除，视为应用从最近任务列表划掉
            // 这是感知应用停止的一个可靠方式
            XposedHelpers.findAndHookMethod(
                amsClass,
                "cleanUpRemovedTask",
                "com.android.server.wm.Task", // Android 10+ TaskRecord is renamed to Task
                Boolean::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val task = param.args[0]
                            // 通过反射获取 Task 对象中的包名
                            val baseIntent = XposedHelpers.getObjectField(task, "mBaseIntent")
                            val componentName = XposedHelpers.callMethod(baseIntent, "getComponent") as ComponentName?
                            val packageName = componentName?.packageName ?: "Unknown"

                            XposedBridge.log("$TAG: App task removed: $packageName")
                            sendEventToDaemon("event.app_killed", mapOf("package_name" to packageName))

                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG: Error in cleanUpRemovedTask hook: ${t.message}")
                        }
                    }
                }
            )
            
            // Hook 'startProcess' - 进程启动
            // 这个 hook 比较复杂，因为有多个重载
            // 【修改】简化Hook查找，使用更通用的参数类型
            XposedHelpers.findAndHookMethod(
                amsClass,
                "startProcess",
                String::class.java, // processName
                ApplicationInfo::class.java, // info
                Boolean::class.java, // knownToBeDead
                Int::class.java, // intentFlags
                String::class.java, // hostingType
                ComponentName::class.java, // hostingName
                Boolean::class.java, // isTopApp
                Boolean::class.java, // isReceiver
                Int::class.java, // userId
                Int::class.java, // sandboxUid
                Boolean::class.java, // isolated
                Int::class.java, // zygotePolicyFlags
                Boolean::class.java, // isSdkSandbox
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val appInfo = param.args[1] as ApplicationInfo?
                        if (appInfo != null) {
                            val packageName = appInfo.packageName
                             XposedBridge.log("$TAG: App process starting: $packageName")
                            sendEventToDaemon("event.app_start", mapOf("package_name" to packageName))
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: Successfully hooked AMS methods for app lifecycle.")

        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook ActivityManagerService.")
            XposedBridge.log(t)
        }
    }
    
    /**
     * 辅助函数，将事件打包成 JSON 并通过 UDS 发送
     */
    private fun sendEventToDaemon(type: String, payload: Map<String, Any>) {
        try {
            val message = mapOf(
                "v" to 1,
                "type" to type,
                "payload" to payload
            )
            val jsonString = gson.toJson(message)
            udsClient.sendMessage(jsonString)
        } catch (e: Exception) {
            XposedBridge.log("$TAG: Error sending event to daemon: ${e.message}")
        }
    }
}