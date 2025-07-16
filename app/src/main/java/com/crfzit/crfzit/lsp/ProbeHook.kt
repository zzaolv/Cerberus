// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.Notification
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

class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val udsClient = UdsClient(probeScope)
    private val gson = Gson()

    companion object {
        private const val TAG = "CerberusProbe"
        private const val PER_USER_RANGE = 100000
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" || lpparam.processName != "android") {
            return
        }
        
        udsClient.start()
        XposedBridge.log("$TAG: Attached to system_server (PID: ${Process.myPid()})")
        
        hookActivityManagerService(lpparam.classLoader)
        hookPowerManagerService(lpparam.classLoader)
        hookNotificationManagerService(lpparam.classLoader)
    }

    // 【新增】Hook 电源管理器，用于监听亮/熄屏
    private fun hookPowerManagerService(classLoader: ClassLoader) {
        try {
            val pmsClass = XposedHelpers.findClass("com.android.server.power.PowerManagerService", classLoader)

            XposedHelpers.findAndHookMethod(pmsClass, "goToSleep", Long::class.java, Int::class.java, Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("$TAG: Screen is turning OFF.")
                        sendEventToDaemon("event.screen_off", emptyMap())
                    }
                })

            XposedHelpers.findAndHookMethod(pmsClass, "wakeUp", Long::class.java, Int::class.java, String::class.java, String::class.java, Int::class.java, String::class.java, Int::class.java,
                object : XC_MethodHook() {
                     override fun afterHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("$TAG: Screen is turning ON.")
                        sendEventToDaemon("event.screen_on", emptyMap())
                    }
                })
             XposedBridge.log("$TAG: Successfully hooked PowerManagerService.")
        } catch(t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook PowerManagerService: ${t.message}")
        }
    }
    
    // 【新增】Hook 通知管理器，用于监听通知
    private fun hookNotificationManagerService(classLoader: ClassLoader) {
        try {
            val nmsClass = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService", classLoader)

            XposedHelpers.findAndHookMethod(nmsClass, "enqueueNotificationWithTag",
                String::class.java, // pkg
                String::class.java, // opPkg
                String::class.java, // tag
                Int::class.java, // id
                Notification::class.java, // notification
                Int::class.java, // userId
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = param.args[0] as String
                        val userId = param.args[5] as Int
                        XposedBridge.log("$TAG: Notification posted for $pkg (user: $userId)")
                        sendEventToDaemon("event.notification_post", mapOf(
                            "package_name" to pkg,
                            "user_id" to userId
                        ))
                    }
                })
            XposedBridge.log("$TAG: Successfully hooked NotificationManagerService.")
        } catch(t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook NotificationManagerService: ${t.message}")
        }
    }


    private fun hookActivityManagerService(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)

            XposedHelpers.findAndHookMethod(amsClass, "cleanUpRemovedTask", "com.android.server.wm.Task", Boolean::class.java, Boolean::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val task = param.args[0]
                            val baseIntent = XposedHelpers.getObjectField(task, "mBaseIntent")
                            val componentName = XposedHelpers.callMethod(baseIntent, "getComponent") as ComponentName?
                            val packageName = componentName?.packageName
                            val userId = XposedHelpers.callMethod(task, "getUserId") as Int
                            
                            if (packageName != null) {
                                sendEventToDaemon("event.app_killed", mapOf("package_name" to packageName, "user_id" to userId))
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG: Error in cleanUpRemovedTask hook: ${t.message}")
                        }
                    }
                }
            )
            
            val startProcessMethods = amsClass.declaredMethods.filter { it.name == "startProcess" }
            startProcessMethods.forEach { method ->
                 XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val appInfo = param.args.firstOrNull { it is ApplicationInfo } as? ApplicationInfo
                        if (appInfo != null) {
                            val packageName = appInfo.packageName
                            val uid = appInfo.uid
                            val userId = uid / PER_USER_RANGE
                            
                            sendEventToDaemon("event.app_start", mapOf(
                                "package_name" to packageName,
                                "user_id" to userId,
                                "uid" to uid
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