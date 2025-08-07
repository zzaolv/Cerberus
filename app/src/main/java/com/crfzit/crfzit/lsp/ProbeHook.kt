// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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
// [核心重构] 不再需要以下导入
// import java.io.IOException
// import java.io.OutputStreamWriter
// import java.net.Socket
// import java.nio.charset.StandardCharsets

class ProbeHook : IXposedHookLoadPackage {

    private val gson = Gson()
    @Volatile private var nmsInstance: Any? = null
    @Volatile private var packageManager: PackageManager? = null

    // [核心重构] 创建一个DaemonConnector的单例
    private lateinit var daemonConnector: DaemonConnector

    companion object {
        private const val TAG = "CerberusProbe_v41_PersistentConn" // 版本号更新
        const val FLAG_INCLUDE_STOPPED_PACKAGES = 32
        private const val DAEMON_HOST = "127.0.0.1"
        private const val DAEMON_PORT = 28900
        private const val USAGE_EVENT_ACTIVITY_RESUMED = 1
        private const val USAGE_EVENT_ACTIVITY_PAUSED = 2

        // [核心重构] 将 ConfigManager 移至顶层，以便 DaemonConnector 可以访问
        object ConfigManager {
            @Volatile var isBqHooked = false
            @Volatile private var frozenUids = emptySet<Int>()

            fun updateConfig(jsonString: String) {
                try {
                    // 注意：守护进程发来的配置更新消息是完整的JSON，我们需要解析它
                    val payload = JsonParser.parseString(jsonString).asJsonObject
                        .getAsJsonObject("payload")
                        ?: return
                    if (payload.has("frozen_uids")) {
                        frozenUids = payload.getAsJsonArray("frozen_uids").map { it.asInt }.toSet()
                    }
                    if (frozenUids.isNotEmpty()) {
                        XposedBridge.log("[$TAG]: Config updated. Tracking ${frozenUids.size} frozen UIDs.")
                    }
                } catch (e: Exception) {
                    XposedBridge.log("[$TAG]: [ERROR] Failed to parse probe config: $e")
                }
            }

            fun isUidFrozen(uid: Int): Boolean = frozenUids.contains(uid)
        }
    }

    private enum class WakeupType(val value: Int) {
        GENERIC_NOTIFICATION(0),
        FCM_PUSH(1),
        PROACTIVE_START(2),
        OTHER(3)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        log("Loading into system_server (PID: ${Process.myPid()}). HookSet active: $TAG")

        // [核心重构] 初始化并启动 DaemonConnector
        daemonConnector = DaemonConnector(DAEMON_HOST, DAEMON_PORT, TAG)
        daemonConnector.start()

        // 注意：probe_hello 消息现在由 DaemonConnector 自动在连接成功后发送
        // sendEventToDaemon("event.probe_hello", mapOf("pid" to Process.myPid(), "version" to TAG))

        try {
            val classLoader = lpparam.classLoader
            hookAmsLifecycle(classLoader)
            hookNmsConstructor(classLoader)
            hookActivitySwitchEvents(classLoader)
            hookTaskTrimming(classLoader)
            hookSystemFreezer(classLoader)
            hookAnrHelper(classLoader)
            hookWakelocksAndAlarms(classLoader)
            hookActivityStarter(classLoader)
            hookNotificationService(classLoader)
            hookServices(classLoader)
        } catch (t: Throwable) {
            logError("CRITICAL: Failed during hook placement: $t")
        }
    }

    // [核心重构] sendEventToDaemon 现在变得非常简单
    private fun sendEventToDaemon(type: String, payload: Any) {
        // 创建消息模型
        val message = CerberusMessage(type = type, payload = payload)
        // 序列化为JSON字符串
        val jsonMessage = gson.toJson(message)
        // 交给连接器发送，无需关心连接细节
        daemonConnector.sendMessage(jsonMessage)
    }

    //
    // --- 所有其他的 Hook 方法 (hookAmsLifecycle, hookNotificationService 等) 保持完全不变 ---
    // --- 它们现在会调用新的、高效的 sendEventToDaemon 方法 ---
    //


    private fun hookAmsLifecycle(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
            val broadcastIntentMethod = amsClass.declaredMethods.find {
                it.name == "broadcastIntentLocked" || it.name == "broadcastIntent"
            }
            if (broadcastIntentMethod == null) {
                logError("FATAL: Could not find AMS#broadcastIntentLocked method.")
                return
            }
            XposedBridge.hookMethod(broadcastIntentMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (ConfigManager.isBqHooked) return
                    val amsInstance = param.thisObject
                    val bqFieldNames = listOf("mFgBroadcastQueue", "mBgBroadcastQueue", "mBroadcastQueues")
                    var bqObject: Any? = null
                    for (fieldName in bqFieldNames) {
                        try {
                            val field = XposedHelpers.findFieldIfExists(amsClass, fieldName) ?: continue
                            val value = field.get(amsInstance) ?: continue
                            val candidate = if (value is List<*> && value.isNotEmpty()) value.first() else value
                            if (candidate != null && candidate !is List<*>) {
                                bqObject = candidate
                                log("Found BroadcastQueue object in field on-demand: $fieldName")
                                break
                            }
                        } catch (t: Throwable) { /* ignore */ }
                    }
                    if (bqObject == null) {
                        logError("WARN: Could not find BroadcastQueue instance on-demand. FCM wakeup might be affected.")
                        ConfigManager.isBqHooked = true
                        return
                    }
                    val concreteBqClass = bqObject.javaClass
                    log("Dynamically found BroadcastQueue class: ${concreteBqClass.name}")
                    findAndHookBroadcastMethod(concreteBqClass)
                    ConfigManager.isBqHooked = true
                }
            })
            log("SUCCESS: Placed dynamic hook on AMS#broadcastIntent for on-demand BQ hooking.")
        } catch (t: Throwable) {
            logError("Could not hook AMS lifecycle for dynamic BQ hooking: ${t.message}")
        }
    }
    private fun findAndHookBroadcastMethod(concreteBqClass: Class<*>) {
        val classLoader = concreteBqClass.classLoader ?: run {
            logError("FATAL: ClassLoader for ${concreteBqClass.name} is null!")
            return
        }
        val brClass = findClass("com.android.server.am.BroadcastRecord", classLoader) ?: run {
            logError("FATAL: Could not find BroadcastRecord class!")
            return
        }
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val record = param.args.firstOrNull { it != null && brClass.isInstance(it) } ?: return
                    val intent = XposedHelpers.getObjectField(record, "intent") as? Intent ?: return
                    if (isGcmOrFcmIntent(intent)) {
                        intent.flags = intent.flags or FLAG_INCLUDE_STOPPED_PACKAGES
                        (XposedHelpers.getObjectField(record, "receivers") as? List<*>)?.forEach { receiver ->
                            try {
                                val uid = (XposedHelpers.getObjectField(receiver, "app") as? Any)?.let { XposedHelpers.getIntField(it, "uid") }
                                    ?: (receiver as? ResolveInfo)?.activityInfo?.applicationInfo?.uid
                                uid?.let { requestWakeupForUid(it, WakeupType.FCM_PUSH) }
                            } catch (ignored: Throwable) {}
                        }
                    }
                } catch (t: Throwable) { logError("Error in BroadcastQueue hook: ${t.message}") }
            }
        }
        val potentialMethodNames = listOf("processNextBroadcast", "processNextBroadcastLocked", "scheduleReceiverLocked", "scheduleReceiverColdLocked")
        var hookedCount = 0
        for (methodName in potentialMethodNames) {
            concreteBqClass.declaredMethods.filter { it.name == methodName }.forEach {
                try {
                    XposedBridge.hookMethod(it, hook)
                    log("SUCCESS: Hooked ${concreteBqClass.simpleName}#${it.name} for robust FCM capture.")
                    hookedCount++
                } catch (t: Throwable) {
                    log("WARN: Failed to hook ${concreteBqClass.simpleName}#${it.name}: ${t.message}")
                }
            }
        }
        if (hookedCount == 0) {
            logError("FATAL: Could not hook any broadcast processing method in ${concreteBqClass.name}.")
        }
    }
    private fun hookNmsConstructor(classLoader: ClassLoader) {
        try {
            val nmsClass = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService", classLoader)
            XposedBridge.hookAllConstructors(nmsClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (nmsInstance == null) {
                        nmsInstance = param.thisObject
                        try {
                            val context = XposedHelpers.callMethod(nmsInstance, "getContext") as Context
                            packageManager = context.packageManager
                            log("SUCCESS: Captured NotificationManagerService instance and PackageManager.")
                        } catch (t: Throwable) {
                            logError("Failed to get Context or PackageManager from NMS instance: $t")
                        }
                    }
                }
            })
        } catch (t: Throwable) { logError("Could not hook NMS constructor: ${t.message}") }
    }
    private fun hookNotificationService(classLoader: ClassLoader) {
        val nmsClass = findClass("com.android.server.notification.NotificationManagerService", classLoader) ?: run {
            logError("FATAL: Could not find NotificationManagerService class!")
            return
        }
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val pm = packageManager ?: return
                try {
                    val pkg = param.args.firstOrNull { it is String } as? String ?: return
                    val notification = param.args.find { it is Notification } as? Notification ?: return
                    val targetUid: Int
                    try {
                        targetUid = pm.getApplicationInfo(pkg, 0).uid
                    } catch (e: PackageManager.NameNotFoundException) { return }
                    catch (t: Throwable) {
                        logError("Unexpected error while getting UID for package '$pkg': ${t.message}")
                        return
                    }
                    if (ConfigManager.isUidFrozen(targetUid)) {
                        val channel = try {
                            nmsInstance?.let { XposedHelpers.callMethod(it, "getNotificationChannelForPackage", pkg, targetUid, notification.channelId, false) as? NotificationChannel }
                        } catch (t: Throwable) { null }
                        if (channel != null && channel.importance < NotificationManager.IMPORTANCE_DEFAULT) {
                            log("Ignoring low-importance notification for frozen UID $targetUid")
                            return
                        }
                        requestWakeupForUid(targetUid, WakeupType.GENERIC_NOTIFICATION)
                    }
                } catch (t: Throwable) { logError("CRITICAL Error in NMS hook: ${t.javaClass.simpleName} - ${t.message}") }
            }
        }
        var hookCount = 0
        nmsClass.declaredMethods.filter { it.name == "enqueueNotificationInternal" }.forEach {
            try { XposedBridge.hookMethod(it, hook); hookCount++ }
            catch (t: Throwable) { logError("Failed to hook a variant of enqueueNotificationInternal: $t") }
        }
        if (hookCount > 0) log("SUCCESS: Hooked $hookCount NMS#enqueueNotificationInternal methods.")
        else logError("FATAL: No NMS#enqueueNotificationInternal methods were hooked.")
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