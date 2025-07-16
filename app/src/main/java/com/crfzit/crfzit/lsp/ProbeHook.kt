// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.content.ComponentName
import android.content.pm.ApplicationInfo
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
import java.lang.reflect.Method

/**
 * 这是我们的 LSPosed 模块的入口点。
 */
class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val udsClient = UdsClient(probeScope)
    private val gson = Gson()

    companion object {
        private const val TAG = "CerberusProbe"
        // Android User ID 分隔符
        private const val PER_USER_RANGE = 100000
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 只注入 system_server 进程
        if (lpparam.packageName != "android" || lpparam.processName != "android") {
            return
        }
        
        // 启动UDS客户端
        udsClient.start()
        XposedBridge.log("$TAG: Attached to system_server (PID: ${Process.myPid()})")
        hookActivityManagerService(lpparam.classLoader)
    }

    private fun hookActivityManagerService(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)

            // Hook cleanUpRemovedTask - 任务被移除，视为应用被杀死
            XposedHelpers.findAndHookMethod(
                amsClass,
                "cleanUpRemovedTask",
                "com.android.server.wm.Task",
                Boolean::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val task = param.args[0]
                            val baseIntent = XposedHelpers.getObjectField(task, "mBaseIntent")
                            val componentName = XposedHelpers.callMethod(baseIntent, "getComponent") as ComponentName?
                            val packageName = componentName?.packageName
                            // 【核心重构】从Task中获取发起者的UserID，这是最可靠的方式
                            val userId = XposedHelpers.callMethod(task, "getUserId") as Int
                            
                            if (packageName != null) {
                                XposedBridge.log("$TAG: App task removed: $packageName, user: $userId")
                                sendEventToDaemon("event.app_killed", mapOf("package_name" to packageName, "user_id" to userId))
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG: Error in cleanUpRemovedTask hook: ${t.message}")
                        }
                    }
                }
            )
            
            // Hook 'startProcess' - 进程启动
            // 找到最通用的 startProcess 方法，它有多个重载
            val startProcessMethods = amsClass.declaredMethods.filter { it.name == "startProcess" }
            startProcessMethods.forEach { method ->
                 XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val appInfo = param.args.firstOrNull { it is ApplicationInfo } as? ApplicationInfo
                        if (appInfo != null) {
                            val packageName = appInfo.packageName
                            // 【核心重构】从 ApplicationInfo 中获取完整的 UID，并计算出 UserID
                            val uid = appInfo.uid
                            val userId = uid / PER_USER_RANGE
                            
                            XposedBridge.log("$TAG: App process starting: $packageName, user: $userId (uid: $uid)")
                            sendEventToDaemon("event.app_start", mapOf(
                                "package_name" to packageName,
                                "user_id" to userId
                            ))
                        }
                    }
                })
            }

            XposedBridge.log("$TAG: Successfully hooked AMS methods for app lifecycle.")

        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook ActivityManagerService.")
            XposedBridge.log(t)
        }
    }
    
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