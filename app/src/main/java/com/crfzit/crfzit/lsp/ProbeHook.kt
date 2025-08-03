// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import com.crfzit.crfzit.data.model.CerberusMessage
import com.google.gson.Gson
import com.google.gson.JsonParser
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets

class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    @Volatile private var nmsInstance: Any? = null

    companion object {
        private const val TAG = "CerberusProbe_v30_ExpertFinal" // 版本号更新
        const val FLAG_INCLUDE_STOPPED_PACKAGES = 32
        private const val DAEMON_HOST = "127.0.0.1"
        private const val DAEMON_PORT = 28900
        private const val USAGE_EVENT_ACTIVITY_RESUMED = 1
        private const val USAGE_EVENT_ACTIVITY_PAUSED = 2
    }
    
    private enum class WakeupType(val value: Int) {
        GENERIC_NOTIFICATION(0), FCM_PUSH(1), PROACTIVE_START(2), OTHER(3)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        log("Loading into system_server (PID: ${Process.myPid()}). Expert-finalized HookSet active: $TAG")
        sendEventToDaemon("event.probe_hello", mapOf("pid" to Process.myPid(), "version" to TAG))

        try {
            val classLoader = lpparam.classLoader
            hookNmsConstructor(classLoader) // 必须先Hook构造函数以获取NMS实例
            hookActivitySwitchEvents(classLoader)
            hookBroadcastDelivery(classLoader) // 已重构
            hookTaskTrimming(classLoader)
            hookSystemFreezer(classLoader)
            hookAnrHelper(classLoader)
            hookWakelocksAndAlarms(classLoader)
            hookActivityStarter(classLoader)
            hookNotificationService(classLoader) // 已重构
            hookServices(classLoader)
        } catch (t: Throwable) {
            logError("CRITICAL: Failed during hook placement: $t")
        }
    }

    private fun sendEventToDaemon(type: String, payload: Any) {
        probeScope.launch {
            val message = CerberusMessage(type = type, payload = payload)
            val jsonMessage = gson.toJson(message)
            try {
                Socket(DAEMON_HOST, DAEMON_PORT).use { socket ->
                    socket.soTimeout = 2000 
                    OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8).use { writer ->
                        writer.write(jsonMessage + "\n"); writer.flush()
                        if (type == "event.probe_hello") {
                            try {
                                socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()?.let {
                                    ConfigManager.updateConfig(it)
                                }
                            } catch (e: Exception) { logError("Failed to read config response after hello: $e") }
                        }
                    }
                }
            } catch (e: IOException) {
                if (e.message?.contains("ECONNREFUSED") != true) {
                    logError("Daemon short-conn send error for $type: ${e.message}")
                }
            } catch (e: Exception) { logError("Unexpected error during short-conn send for $type: $e") }
        }
    }

    private fun requestWakeupForUid(uid: Int, type: WakeupType) {
        if (uid >= Process.FIRST_APPLICATION_UID && ConfigManager.isUidFrozen(uid)) {
            val reason = when(type) {
                WakeupType.FCM_PUSH -> "FCM"
                WakeupType.GENERIC_NOTIFICATION -> "Notification"
                else -> "Wakeup"
            }
            log("WAKEUP: Requesting temporary unfreeze for UID $uid, Reason: $reason")
            sendEventToDaemon("event.app_wakeup_request_v2", mapOf("uid" to uid, "type_int" to type.value))
        }
    }
    
    // [核心重构] 1. 修正通知解冻逻辑
    private fun hookNmsConstructor(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookConstructor(
                "com.android.server.notification.NotificationManagerService", classLoader,
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        nmsInstance = param.thisObject
                        log("SUCCESS: Captured NotificationManagerService instance.")
                    }
                }
            )
        } catch (t: Throwable) {
            logError("Could not hook NMS constructor: ${t.message}")
        }
    }

    private fun hookNotificationService(classLoader: ClassLoader) {
        val nmsClass = findClass("com.android.server.notification.NotificationManagerService", classLoader) ?: run {
            logError("FATAL: Could not find NotificationManagerService class!")
            return
        }
        
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val instance = nmsInstance ?: return
                try {
                    val pkg = param.args[0] as String
                    val opPkg = param.args[1] as String
                    val callingUid = param.args[2] as Int
                    val notification = param.args.find { it is Notification } as? Notification ?: return
                    val incomingUserId = param.args.find { arg -> arg is Int && arg != callingUid } as? Int ?: Process.myUid()

                    // 步骤1: 反射调用内部方法，获取真正的目标UID
                    val targetUid = XposedHelpers.callMethod(instance, "resolveNotificationUid", opPkg, pkg, callingUid, incomingUserId) as Int
                    
                    if (targetUid < Process.FIRST_APPLICATION_UID || !ConfigManager.isUidFrozen(targetUid)) {
                        return
                    }

                    // 步骤2: 检查通知渠道重要性，避免为无声通知唤醒
                    val channel = XposedHelpers.callMethod(instance, "getNotificationChannelForPackage", pkg, targetUid, notification.channelId, false) as? NotificationChannel
                    if (channel != null && channel.importance < NotificationManager.IMPORTANCE_DEFAULT) {
                        log("WAKEUP: Skipped low-importance notification for UID $targetUid")
                        return
                    }

                    requestWakeupForUid(targetUid, WakeupType.GENERIC_NOTIFICATION)

                } catch (t: Throwable) {
                    logError("Error in afterHookedMethod of NMS: ${t.message}")
                }
            }
        }

        // 优先尝试精确签名
        try {
            XposedHelpers.findAndHookMethod(nmsClass, "enqueueNotificationInternal",
                String::class.java, String::class.java, Int::class.java, Int::class.java, String::class.java,
                Int::class.java, Notification::class.java, Int::class.java, hook)
            log("SUCCESS: Hooked NMS#enqueueNotificationInternal (precise signature).")
        } catch (e: Throwable) {
            logError("Could not find precise NMS#enqueueNotificationInternal, falling back to broad search.")
            nmsClass.declaredMethods.filter { it.name == "enqueueNotificationInternal" }.forEach { XposedBridge.hookMethod(it, hook) }
        }
    }

    // [核心重构] 2. 切换到正确的广播Hook点
    private fun hookBroadcastDelivery(classLoader: ClassLoader) {
        val bqClass = findClass("com.android.server.am.BroadcastQueue", classLoader) ?: run {
            logError("FATAL: Could not find BroadcastQueue class!")
            return
        }

        // Hook点1: 分发给动态注册的接收者
        try {
            val broadcastFilterClass = XposedHelpers.findClass("com.android.server.am.BroadcastFilter", classLoader)
            val broadcastRecordClass = XposedHelpers.findClass("com.android.server.am.BroadcastRecord", classLoader)
            XposedHelpers.findAndHookMethod(bqClass, "deliverToRegisteredReceiverLocked",
                broadcastRecordClass, broadcastFilterClass, Boolean::class.java, Int::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val record = param.args[0]
                            val filter = param.args[1]
                            val intent = XposedHelpers.getObjectField(record, "intent") as? Intent ?: return
                            if (isGcmOrFcmIntent(intent)) {
                                val receiverList = XposedHelpers.getObjectField(filter, "receiverList")
                                val app = XposedHelpers.getObjectField(receiverList, "app")
                                if (app != null) {
                                    val uid = XposedHelpers.getIntField(app, "uid")
                                    requestWakeupForUid(uid, WakeupType.FCM_PUSH)
                                }
                            }
                        } catch (t: Throwable) {
                            logError("Error in deliverToRegisteredReceiverLocked hook: ${t.message}")
                        }
                    }
                }
            )
            log("SUCCESS: Hooked BroadcastQueue#deliverToRegisteredReceiverLocked.")
        } catch (t: Throwable) {
            logError("Failed to hook deliverToRegisteredReceiverLocked: ${t.message}")
        }

        // Hook点2: 调度冷启动静态注册的接收者
        try {
            val resolveInfoClass = XposedHelpers.findClass("android.content.pm.ResolveInfo", classLoader)
            bqClass.declaredMethods.find { it.name == "scheduleReceiverColdLocked" }?.let {
                XposedBridge.hookMethod(it, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val intent = param.args[0] as? Intent ?: return
                            val info = param.args[1] as? ResolveInfo ?: return
                            if (isGcmOrFcmIntent(intent)) {
                                val uid = info.activityInfo.applicationInfo.uid
                                requestWakeupForUid(uid, WakeupType.FCM_PUSH)
                            }
                        } catch (t: Throwable) {
                            logError("Error in scheduleReceiverColdLocked hook: ${t.message}")
                        }
                    }
                })
                log("SUCCESS: Hooked BroadcastQueue#scheduleReceiverColdLocked.")
            }
        } catch (t: Throwable) {
            logError("Failed to hook scheduleReceiverColdLocked: ${t.message}")
        }
        
        // 保留AMS Hook作为双重保险，尽早设置FLAG
        findClass("com.android.server.am.ActivityManagerService", classLoader)?.let { amsClass ->
            amsClass.declaredMethods.find { it.name == "broadcastIntentLocked" }?.let { 
                XposedBridge.hookMethod(it, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        (param.args.find { it is Intent } as? Intent)?.let {
                            if (isGcmOrFcmIntent(it)) it.flags = it.flags or FLAG_INCLUDE_STOPPED_PACKAGES
                        }
                    }
                })
                log("SUCCESS: Hooked AMS#broadcastIntentLocked for setting FCM flags early.")
            }
        }
    }
    
    // ... 其他所有被评估为“没有问题”的Hook保持不变 ...
    private object ConfigManager {
        @Volatile private var frozenUids = emptySet<Int>()
        fun updateConfig(jsonString: String) {
            try {
                val payload = JsonParser.parseString(jsonString).asJsonObject.getAsJsonObject("payload")
                if (payload.has("frozen_uids")) {
                    frozenUids = payload.getAsJsonArray("frozen_uids").map { it.asInt }.toSet()
                }
                if (frozenUids.isNotEmpty()) XposedBridge.log("[$TAG]: Config updated. Tracking ${frozenUids.size} UIDs.")
            } catch (e: Exception) { XposedBridge.log("[$TAG]: [ERROR] Failed to parse probe config: $e") }
        }
        fun isUidFrozen(uid: Int): Boolean = frozenUids.contains(uid)
    }
    private fun log(message: String) = XposedBridge.log("[$TAG] $message")
    private fun logError(message: String) = XposedBridge.log("[$TAG] [ERROR] $message")
    private fun findClass(className: String, classLoader: ClassLoader): Class<*>? = XposedHelpers.findClassIfExists(className, classLoader)
    private fun hookActivitySwitchEvents(classLoader: ClassLoader) {
        findClass("com.android.server.am.ActivityManagerService", classLoader)?.let { clazz ->
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val componentName = param.args[0] as? ComponentName ?: return
                        val userId = param.args[1] as Int
                        val event = param.args[2] as Int
                        sendEventToDaemon(
                            if (event == USAGE_EVENT_ACTIVITY_RESUMED) "event.app_foreground" else "event.app_background",
                            AppInstanceKey(componentName.packageName, userId)
                        )
                    } catch (t: Throwable) { logError("Error in hookActivitySwitchEvents: ${t.message}") }
                }
            }
            clazz.declaredMethods.find { it.name == "updateActivityUsageStats" && it.parameterCount >= 3 }
                ?.let { XposedBridge.hookMethod(it, hook); log("SUCCESS: Hooked ActivityManagerService#updateActivityUsageStats.") }
                ?: logError("FATAL: Could not find ActivityManagerService#updateActivityUsageStats method!")
        } ?: logError("FATAL: Could not find com.android.server.am.ActivityManagerService class!")
    }
    private fun hookTaskTrimming(classLoader: ClassLoader) {
        findClass("com.android.server.wm.RecentTasks", classLoader)?.let {
            XposedBridge.hookAllMethods(it, "trimInactiveRecentTasks", XC_MethodReplacement.DO_NOTHING)
            log("SUCCESS: Hooked and disabled RecentTasks#trimInactiveRecentTasks.")
        } ?: logError("WARN: Could not find com.android.server.wm.RecentTasks class.")
    }
    private fun hookSystemFreezer(classLoader: ClassLoader) {
        findClass("com.android.server.am.CachedAppOptimizer", classLoader)?.let {
            XposedBridge.hookAllMethods(it, "useFreezer", XC_MethodReplacement.returnConstant(false))
            log("SUCCESS: Hooked and disabled system's CachedAppOptimizer freezer.")
        } ?: log("INFO: Did not find com.android.server.am.CachedAppOptimizer (normal on older Android).")
    }
    private fun hookAnrHelper(classLoader: ClassLoader) {
        findClass("com.android.server.am.AnrHelper", classLoader)?.let { clazz ->
            val anrHook = object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val processRecord = param.args.find { it != null && it.javaClass.name.endsWith("ProcessRecord") } ?: return
                    if (ConfigManager.isUidFrozen(XposedHelpers.getIntField(processRecord, "uid"))) {
                        log("PROTECT: Suppressing ANR for frozen UID: ${XposedHelpers.getIntField(processRecord, "uid")}")
                        param.result = null
                    }
                }
            }
            clazz.declaredMethods.filter { it.name.contains("appNotResponding") }.forEach { XposedBridge.hookMethod(it, anrHook) }
            log("SUCCESS: Hooked all ANR methods in AnrHelper.")
        }
    }
    private fun hookWakelocksAndAlarms(classLoader: ClassLoader) {
        findClass("com.android.server.power.PowerManagerService", classLoader)?.let { pmsClass ->
            val wlHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val uidArg = param.args.find { it is Int && it >= Process.FIRST_APPLICATION_UID } as? Int
                    if (uidArg != null && ConfigManager.isUidFrozen(uidArg)) {
                        log("DEFENSE: Blocked acquireWakeLock for frozen uid: $uidArg")
                        param.result = null
                    }
                }
            }
            pmsClass.declaredMethods.filter { it.name.startsWith("acquireWakeLock") }.forEach { XposedBridge.hookMethod(it, wlHook) }
            log("SUCCESS: Hooked PowerManagerService wakelock acquisition methods.")
        }
        findClass("com.android.server.alarm.AlarmManagerService", classLoader)?.let { amsClass ->
            val alarmHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    (param.args.firstOrNull { it is ArrayList<*> } as? ArrayList<*>)?.removeIf { alarm ->
                        ConfigManager.isUidFrozen(XposedHelpers.getIntField(alarm!!, "uid")).also {
                            if (it) log("DEFENSE: Blocked alarm for frozen UID: ${XposedHelpers.getIntField(alarm, "uid")}")
                        }
                    }
                }
            }
            amsClass.declaredMethods.filter { it.name.contains("triggerAlarms") }.forEach { XposedBridge.hookMethod(it, alarmHook) }
            log("SUCCESS: Hooked AlarmManagerService alarm trigger methods.")
        }
    }
    private fun hookActivityStarter(classLoader: ClassLoader) {
        findClass("com.android.server.wm.ActivityStarter", classLoader)?.let { clazz ->
            val executeHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val request = param.args.find { it != null && it.javaClass.name == "com.android.server.wm.ActivityStarter\$Request" } ?: return
                        if (XposedHelpers.getIntField(request, "callingUid") >= Process.FIRST_APPLICATION_UID) {
                            (XposedHelpers.getObjectField(request, "intent") as? Intent)?.component?.packageName?.let { pkg ->
                                val userId = XposedHelpers.getIntField(request, "userId")
                                log("PROACTIVE: Activity start for $pkg (user $userId). Requesting unfreeze.")
                                sendEventToDaemon("cmd.proactive_unfreeze", AppInstanceKey(pkg, userId))
                            }
                        }
                    } catch (t: Throwable) { logError("Error in ActivityStarter#execute hook: $t") }
                }
            }
            XposedBridge.hookAllMethods(clazz, "execute", executeHook)
            log("SUCCESS: Hooked ActivityStarter#execute for proactive unfreezing.")
        } ?: logError("FATAL: Could not find ActivityStarter class!")
    }
    private fun hookServices(classLoader: ClassLoader) {
        findClass("com.android.server.am.ActiveServices", classLoader)?.let { clazz ->
             val hook = object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val serviceRecord = param.args.find { it != null && it.javaClass.name.endsWith("ServiceRecord") } ?: return
                    val appInfo = XposedHelpers.getObjectField(serviceRecord, "appInfo") as? ApplicationInfo ?: return
                    if (ConfigManager.isUidFrozen(appInfo.uid)) {
                        log("DEFENSE: Blocked service startup for frozen app: ${appInfo.packageName}")
                        param.result = null
                    }
                }
            }
            clazz.declaredMethods.filter { it.name.contains("bringUpService") }.forEach { XposedBridge.hookMethod(it, hook) }
            log("SUCCESS: Hooked ActiveServices service startup methods.")
        }
    }
    private fun isGcmOrFcmIntent(intent: Intent): Boolean {
        return intent.action?.let { it == "com.google.android.c2dm.intent.RECEIVE" || it == "com.google.firebase.MESSAGING_EVENT" } ?: false
    }
    private data class AppInstanceKey(val package_name: String, val user_id: Int)
}