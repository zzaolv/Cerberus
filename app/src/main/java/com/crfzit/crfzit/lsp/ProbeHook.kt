// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.IIntentReceiver
import android.os.Handler
import android.os.PowerManager
import android.os.Process
import com.crfzit.crfzit.data.model.AppInstanceKey
import com.crfzit.crfzit.data.model.CerberusMessage
import com.crfzit.crfzit.data.model.FullConfigPayload
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import com.google.gson.JsonParser
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@OptIn(DelicateCoroutinesApi::class)
class ProbeHook : IXposedHookLoadPackage {

    private val scope = GlobalScope
    private var udsClient: UdsClient? = null
    private val gson = Gson()
    private var powerManager: PowerManager? = null

    // 用于跟踪每个进程的前台状态，以避免重复发送事件
    private val foregroundStatusCache = ConcurrentHashMap<Int, Boolean>()

    companion object {
        private const val TAG = "CerberusProbe_v3_Reborn"
        private const val PER_USER_RANGE = 100000
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") {
            log("Loading into system_server (PID: ${Process.myPid()}).")
            val singleThreadContext = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            udsClient = UdsClient(scope)
            scope.launch(singleThreadContext) {
                setupPersistentUdsCommunication()
            }
            try {
                hookActivityManagerService(lpparam.classLoader)
                hookWakeLockAndAlarm(lpparam.classLoader)
                hookBroadcastQueue(lpparam.classLoader)
            } catch (t: Throwable) {
                logError("CRITICAL: Failed during hook placement: $t")
            }
        }
    }

    private fun hookActivityManagerService(classLoader: ClassLoader) {
        try {
            // 这个Hook用于获取PowerManager实例，并Hook进程状态更新的核心方法
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
            XposedBridge.hookAllConstructors(amsClass, object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (powerManager == null) {
                        powerManager = XposedHelpers.getObjectField(param.thisObject, "mPowerManager") as? PowerManager
                        log(if (powerManager != null) "Successfully got PowerManager instance." else "Failed to get PowerManager from AMS.")
                    }
                }
            })

            // [核心Hook] 监听应用进程状态变化，上报前后台切换事件
            // updateLruProcessLSP 是 Android 8.0 之后更新进程状态的通用入口点
            val processListClass = XposedHelpers.findClass("com.android.server.am.ProcessList", classLoader)
            XposedHelpers.findAndHookMethod(
                processListClass,
                "updateLruProcessLSP", // LSPosed 提供的更稳定的Hook点
                XposedHelpers.findClass("com.android.server.am.ProcessRecord", classLoader),
                Boolean::class.javaPrimitiveType,
                Any::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val process = param.args[0] as Any
                        val appInfo = XposedHelpers.getObjectField(process, "info") as? android.content.pm.ApplicationInfo ?: return
                        val uid = appInfo.uid
                        if (uid < Process.FIRST_APPLICATION_UID) return

                        val isInteresting = XposedHelpers.callMethod(process, "isInterestingToUser") as Boolean
                        val hasFgs = XposedHelpers.callMethod(process, "hasForegroundServices") as Boolean
                        val isForeground = isInteresting || hasFgs // 简化判断：对用户可见或有前台服务即为前台

                        val lastStatus = foregroundStatusCache[uid]
                        if (lastStatus == isForeground) return // 状态未改变，无需上报

                        foregroundStatusCache[uid] = isForeground
                        val packageName = appInfo.packageName
                        val userId = uid / PER_USER_RANGE

                        if (isForeground) {
                            log("EVENT: App became foreground: $packageName (user: $userId)")
                            sendEventToDaemon("event.app_foreground", mapOf("package_name" to packageName, "user_id" to userId))
                        } else {
                            log("EVENT: App became background: $packageName (user: $userId)")
                            sendEventToDaemon("event.app_background", mapOf("package_name" to packageName, "user_id" to userId))
                        }
                    }
                }
            )
            log("Successfully hooked ProcessList#updateLruProcessLSP for foreground/background events.")

        } catch (t: Throwable) {
            logError("Error hooking ActivityManagerService or ProcessList: $t")
        }
    }

    private fun hookWakeLockAndAlarm(classLoader: ClassLoader) {
        try {
            // [唤醒拦截] Hook acquireWakeLockInternal 来阻止为已冻结应用获取唤醒锁
            val pmsClass = XposedHelpers.findClass("com.android.server.power.PowerManagerService", classLoader)
            XposedHelpers.findAndHookMethod(pmsClass, "acquireWakeLockInternal",
                android.os.IBinder::class.java, Int::class.javaPrimitiveType, String::class.java, String::class.java,
                android.os.WorkSource::class.java, String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val uid = param.args[6] as Int
                        if (ConfigManager.isUidFrozen(uid)) {
                            val packageName = param.args[3] as String
                            log("DEFENSE: Blocked acquireWakeLock for frozen app: $packageName (uid: $uid)")
                            param.result = null // 阻止原始方法执行
                        }
                    }
                }
            )
            log("Successfully hooked PowerManagerService#acquireWakeLockInternal.")
        } catch (t: Throwable) {
            logError("Failed to hook PowerManagerService: $t")
        }

        try {
            // [唤醒拦截] Hook AlarmManagerService.triggerAlarmsLocked 来过滤掉发往已冻结应用的闹钟
            val amsClass = XposedHelpers.findClass("com.android.server.alarm.AlarmManagerService", classLoader)
            XposedHelpers.findAndHookMethod(amsClass, "triggerAlarmsLocked",
                ArrayList::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                object: XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val triggerList = param.args[0] as? ArrayList<*> ?: return
                        triggerList.removeIf { alarm ->
                            val uid = XposedHelpers.getIntField(alarm!!, "uid")
                            if (ConfigManager.isUidFrozen(uid)) {
                                val packageName = XposedHelpers.getObjectField(alarm, "mTag") as? String ?: "unknown"
                                log("DEFENSE: Blocked alarm for frozen app: $packageName (uid: $uid)")
                                true // 返回true表示从列表中移除
                            } else {
                                false
                            }
                        }
                    }
                }
            )
             log("Successfully hooked AlarmManagerService#triggerAlarmsLocked.")
        } catch (t: Throwable) {
            logError("Failed to hook AlarmManagerService: $t")
        }
    }

    private fun hookBroadcastQueue(classLoader: ClassLoader) {
        try {
            // [唤醒拦截] Hook deliverToRegisteredReceiverLocked 来阻止动态广播唤醒
            val bqClass = XposedHelpers.findClass("com.android.server.am.BroadcastQueue", classLoader)
            XposedHelpers.findAndHookMethod(bqClass, "deliverToRegisteredReceiverLocked",
                XposedHelpers.findClass("com.android.server.am.BroadcastRecord", classLoader),
                XposedHelpers.findClass("com.android.server.am.BroadcastFilter", classLoader),
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object: XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val filter = param.args[1]
                        val uid = XposedHelpers.getIntField(filter, "owningUid")
                        if (ConfigManager.isUidFrozen(uid)) {
                            val br = param.args[0]
                            val action = XposedHelpers.getObjectField(br, "intent")?.let { XposedHelpers.callMethod(it, "getAction") } ?: "unknown action"
                            log("DEFENSE: Blocked dynamic broadcast ($action) for frozen uid: $uid")
                            // 模拟系统跳过此接收者
                            val delivery = XposedHelpers.getObjectField(br, "delivery") as? IntArray
                            val index = param.args[3] as Int
                            if (delivery != null && index < delivery.size) {
                                delivery[index] = 2 // DELIVERY_SKIPPED
                            }
                            param.result = null
                        }
                    }
                }
            )
             log("Successfully hooked BroadcastQueue#deliverToRegisteredReceiverLocked.")
        } catch(t: Throwable) {
            logError("Failed to hook BroadcastQueue: $t")
        }
    }


    private suspend fun setupPersistentUdsCommunication() {
        log("Persistent communication manager started.")
        while (scope.isActive) {
            try {
                udsClient?.start()
                delay(1000)
                sendEventToDaemon("event.probe_hello", mapOf("pid" to Process.myPid(), "version" to TAG))
                udsClient?.incomingMessages?.collect { jsonLine: String ->
                    try {
                        ConfigManager.updateConfig(jsonLine)
                    } catch (e: Exception) {
                        logError("Error processing config message: $e")
                    }
                }
                logError("UDS message stream ended. Restarting cycle.")
                udsClient?.stop()
            } catch (e: Exception) {
                logError("Exception in communication cycle: $e. Restarting.")
            }
            delay(5000L)
        }
    }

    private fun sendEventToDaemon(type: String, payload: Any) {
        scope.launch {
            try {
                val message = CerberusMessage(type = type, payload = payload)
                udsClient?.sendMessage(gson.toJson(message))
            } catch (e: Exception) {
                logError("Daemon send error: $e")
            }
        }
    }

    private object ConfigManager {
        @Volatile private var frozenUids = emptySet<Int>()
        private val jsonParser = JsonParser()

        fun updateConfig(jsonString: String) {
            try {
                val root = jsonParser.parse(jsonString).asJsonObject
                if (root.has("payload") && root["payload"].isJsonObject) {
                    val payload = root.getAsJsonObject("payload")
                    if (payload.has("frozen_uids") && payload["frozen_uids"].isJsonArray) {
                        val uids = payload.getAsJsonArray("frozen_uids").map { it.asInt }.toSet()
                        frozenUids = uids
                        XposedBridge.log("$TAG: Config updated. Now tracking ${uids.size} frozen uids.")
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("$TAG: [ERROR] Failed to parse probe config: $e. JSON: $jsonString")
            }
        }

        fun isUidFrozen(uid: Int): Boolean {
            return frozenUids.contains(uid)
        }
    }
    
    private fun log(message: String) = XposedBridge.log("[$TAG] $message")
    private fun logError(message: String) = XposedBridge.log("[$TAG] [ERROR] $message")
}