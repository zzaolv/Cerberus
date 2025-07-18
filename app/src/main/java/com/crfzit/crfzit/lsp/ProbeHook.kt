// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.ActivityManager
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
 * Project Cerberus - System Probe.
 * 职责：捕获系统事件，格式化为JSON，发送给守护进程。不包含任何决策逻辑。
 */
class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val udsClient = UdsClient(probeScope)
    private val gson = Gson()

    companion object {
        private const val TAG = "CerberusProbe"
        // Android User ID 的计算基数
        private const val PER_USER_RANGE = 100000
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 严格确保只注入 system_server 进程
        if (lpparam.packageName != "android" || lpparam.processName != "android") {
            return
        }
        
        // 启动与守护进程的UDS通信
        udsClient.start()
        log("Attached to system_server (PID: ${Process.myPid()}). Probe is active.")
        
        hookActivityManagerService(lpparam.classLoader)
        // 未来可以增加对 PowerManagerService, NotificationManagerService 等的 Hook
    }

    private fun hookActivityManagerService(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)

            // Hook: 应用启动
            // 这个方法是启动应用进程的核心，参数中有 ApplicationInfo
            val startProcessMethods = amsClass.declaredMethods.filter { it.name == "startProcess" }
            startProcessMethods.forEach { method ->
                 XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val appInfo = param.args.firstOrNull { it is ApplicationInfo } as? ApplicationInfo ?: return
                        val packageName = appInfo.packageName
                        // 从 ApplicationInfo 中获取完整的 UID，并计算出 UserID
                        val uid = appInfo.uid
                        val userId = uid / PER_USER_RANGE
                        
                        log("Event: App process starting. Pkg: $packageName, User: $userId")
                        sendEventToDaemon(
                            "event.app_start",
                            mapOf("package_name" to packageName, "user_id" to userId)
                        )
                    }
                })
            }

            // Hook: 应用被真正杀死 (任务被移除)
            // 'cleanUpRemovedTask' 是一个非常好的 Hook 点，表示用户从最近任务列表划掉一个应用
            XposedHelpers.findAndHookMethod(
                amsClass,
                "cleanUpRemovedTask",
                // 不同安卓版本，TaskRecord 的类名可能在不同的包下
                XposedHelpers.findClass("com.android.server.wm.Task", classLoader),
                Boolean::class.javaPrimitiveType, // wasKilled
                Boolean::class.javaPrimitiveType, // detach
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val task = param.args[0] ?: return
                        try {
                            val realActivity = XposedHelpers.getObjectField(task, "realActivity") as ComponentName?
                            val packageName = realActivity?.packageName ?: return
                            val userId = XposedHelpers.callMethod(task, "getUserId") as Int
                            
                            log("Event: App task removed. Pkg: $packageName, User: $userId")
                            sendEventToDaemon(
                                "event.app_killed",
                                mapOf("package_name" to packageName, "user_id" to userId)
                            )
                        } catch (t: Throwable) {
                            logError("Error in cleanUpRemovedTask hook: $t")
                        }
                    }
                }
            )
            log("Successfully hooked AMS methods for app lifecycle.")
        } catch (t: Throwable) {
            logError("Failed to hook ActivityManagerService: $t")
        }
    }
    
    /**
     * 将事件格式化为JSON并发送给守护进程
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
            logError("Error sending event '$type' to daemon: ${e.message}")
        }
    }

    private fun log(message: String) = XposedBridge.log("$TAG: $message")
    private fun logError(message: String) = XposedBridge.log("$TAG: [ERROR] $message")
}