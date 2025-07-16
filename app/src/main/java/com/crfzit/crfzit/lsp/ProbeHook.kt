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

    // 【核心修复】ProbeHook 自身也需要一个独立的、与 system_server 同生命周期的 CoroutineScope
    private val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 【核心修复】通过 getInstance() 获取全局单例
    private val udsClient = UdsClient.getInstance(probeScope)
    private val gson = Gson()

    companion object {
        private const val TAG = "CerberusProbe"
        private const val PER_USER_RANGE = 100000
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" || lpparam.processName != "android") {
            return
        }

        // 【核心修复】不再需要调用 udsClient.start()
        // 它在被 getInstance() 首次调用时就会自动启动

        XposedBridge.log("$TAG: Attached to system_server (PID: ${Process.myPid()}). UDS Client is managed globally.")

        hookActivityManagerService(lpparam.classLoader)
        hookPowerManagerService(lpparam.classLoader)
        hookNotificationManagerService(lpparam.classLoader)
    }

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

            // 寻找更通用的 wakeUp 方法
            val wakeUpMethod = pmsClass.declaredMethods.firstOrNull {
                it.name == "wakeUp" && it.parameterCount >= 2 && it.parameterTypes[0] == Long::class.java && it.parameterTypes[1] == Int::class.java
            }
            if (wakeUpMethod != null) {
                XposedBridge.hookMethod(wakeUpMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("$TAG: Screen is turning ON.")
                        sendEventToDaemon("event.screen_on", emptyMap())
                    }
                })
                XposedBridge.log("$TAG: Successfully hooked PowerManagerService.wakeUp.")
            } else {
                XposedBridge.log("$TAG: Could not find a suitable wakeUp method to hook.")
            }

        } catch(t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook PowerManagerService: ${t.message}")
        }
    }

    private fun hookNotificationManagerService(classLoader: ClassLoader) {
        try {
            val nmsClass = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService", classLoader)

            // 找到一个在大多数Android版本中都存在的、更稳定的方法签名
            val enqueueMethod = nmsClass.declaredMethods.firstOrNull {
                it.name == "enqueueNotificationWithTag" && it.parameterTypes.size >= 6
            }

            if (enqueueMethod != null) {
                XposedBridge.hookMethod(enqueueMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val pkg = param.args[0] as String
                            // userId 的位置可能变化，尝试从末尾或固定位置获取
                            val userId = param.args.firstOrNull { arg -> arg is Int && arg < 20 } as? Int ?: param.args.getOrNull(5) as? Int ?: 0

                            XposedBridge.log("$TAG: Notification posted for $pkg (user: $userId)")
                            sendEventToDaemon("event.notification_post", mapOf(
                                "package_name" to pkg,
                                "user_id" to userId
                            ))
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG: Error inside enqueueNotificationWithTag hook: ${t.message}")
                        }
                    }
                })
                XposedBridge.log("$TAG: Successfully hooked NotificationManagerService.enqueueNotificationWithTag.")
            } else {
                XposedBridge.log("$TAG: Could not find a suitable enqueueNotificationWithTag method to hook.")
            }

        } catch(t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook NotificationManagerService: ${t.message}")
        }
    }


    private fun hookActivityManagerService(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)

            val cleanUpMethod = amsClass.declaredMethods.firstOrNull { it.name == "cleanUpRemovedTask" }
            if (cleanUpMethod != null) {
                XposedBridge.hookMethod(cleanUpMethod, object : XC_MethodHook() {
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
                })
                XposedBridge.log("$TAG: Successfully hooked AMS.cleanUpRemovedTask.")
            }

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
            XposedBridge.log("$TAG: Successfully hooked ${startProcessMethods.size} AMS.startProcess methods.")

        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook ActivityManagerService: ${t.message}")
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