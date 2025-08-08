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
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue

class ProbeHook : IXposedHookLoadPackage {

    private val gson = Gson()
    @Volatile private var nmsInstance: Any? = null
    @Volatile private var packageManager: PackageManager? = null

    companion object {
        private const val TAG = "CerberusProbe_v42_AsyncComm"
        const val FLAG_INCLUDE_STOPPED_PACKAGES = 32
        private const val DAEMON_HOST = "127.0.0.1"
        private const val DAEMON_PORT = 28900
        private const val USAGE_EVENT_ACTIVITY_RESUMED = 1
        private const val USAGE_EVENT_ACTIVITY_PAUSED = 2

        // [修正] 将日志函数移动到 companion object 中，使其成为静态可访问的。
        // 这样，内部的 CommManager 和 ConfigManager 就可以正确调用它们。
        private fun log(message: String) = XposedBridge.log("[$TAG] $message")
        private fun logError(message: String) = XposedBridge.log("[$TAG] [ERROR] $message")
    }

    private enum class WakeupType(val value: Int) {
        GENERIC_NOTIFICATION(0),
        FCM_PUSH(1),
        PROACTIVE_START(2),
        OTHER(3)
    }

    /**
     * [核心重构] handleLoadPackage 现在只负责初始化CommManager工作线程。
     * 所有的通信任务都被委托给CommManager，实现了异步和解耦。
     */
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        log("Loading into system_server (PID: ${Process.myPid()}). HookSet active: $TAG")

        // 启动事件发送工作线程
        CommManager.start()

        // 首次启动时，发送一个 "hello" 事件，这个事件会排在队列的最前面。
        // 工作线程将处理它并获取初始配置。
        CommManager.sendEvent("event.probe_hello", mapOf("pid" to Process.myPid(), "version" to TAG))

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

    /**
     * [核心重构] CommManager (通信管理器)
     * 这是一个单例对象，负责所有与守护进程的通信。
     * 它采用 "生产者-消费者" 模型，将Hook点（生产者）与网络IO（消费者）解耦。
     */
    private object CommManager {
        // 使用线程安全的阻塞队列作为事件缓冲区
        private val eventQueue = LinkedBlockingQueue<Pair<String, Any>>()
        private var workerThread: Thread? = null
        private val gson = Gson()

        /**
         * 启动唯一的后台工作线程。此方法是幂等的。
         */
        fun start() {
            if (workerThread?.isAlive == true) return
            workerThread = Thread {
                log("CommManager worker thread started.")
                while (true) {
                    try {
                        // 从队列中阻塞式地获取一个事件，如果没有事件，线程会在此处休眠，不消耗CPU
                        val (type, payload) = eventQueue.take()
                        val message = CerberusMessage(type = type, payload = payload)
                        val jsonMessage = gson.toJson(message)

                        // 使用短连接发送事件
                        try {
                            Socket(DAEMON_HOST, DAEMON_PORT).use { socket ->
                                socket.soTimeout = 2000 // 2秒超时
                                OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8).use { writer ->
                                    writer.write(jsonMessage + "\n")
                                    writer.flush()

                                    // 只有 "hello" 事件需要等待并处理响应，以获取初始配置
                                    if (type == "event.probe_hello") {
                                        try {
                                            socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()?.let {
                                                ConfigManager.updateConfig(it)
                                            }
                                        } catch (e: Exception) {
                                            logError("Failed to read config response after hello: $e")
                                        }
                                    }
                                }
                            }
                        } catch (e: IOException) {
                            // 连接被拒绝是正常情况（守护进程可能还没启动），不需要刷屏报错
                            if (e.message?.contains("ECONNREFUSED") != true) {
                                logError("Daemon short-conn send error for '$type': ${e.message}")
                            }
                        } catch (e: Exception) {
                            logError("Unexpected error during short-conn send for '$type': $e")
                        }
                    } catch (ie: InterruptedException) {
                        // 线程被中断，正常退出循环
                        Thread.currentThread().interrupt()
                        break
                    } catch (t: Throwable) {
                        logError("FATAL: Error in CommManager worker thread: ${t.message}")
                        // 即使发生未知错误，也休眠一下防止CPU空转
                        Thread.sleep(1000)
                    }
                }
                log("CommManager worker thread stopped.")
            }.apply {
                name = "CerberusCommThread"
                // 降低优先级，减少对 system_server 的影响
                priority = Thread.NORM_PRIORITY - 1
                isDaemon = true // 设为守护线程
                start()
            }
        }

        /**
         * 外部调用此方法将事件放入队列。此方法是非阻塞的，速度极快。
         * @param type 事件类型，如 "event.app_foreground"
         * @param payload 事件的负载数据
         */
        fun sendEvent(type: String, payload: Any) {
            // offer是非阻塞的，如果队列满了会返回false，但LinkedBlockingQueue默认是无界的
            eventQueue.offer(type to payload)
        }
    }


    /**
     * [核心重构] 所有 sendEventToDaemon 的调用都改为 CommManager.sendEvent。
     * 这个操作非常轻量级，只是向队列中添加一个对象，不会创建线程或Socket。
     */
    private fun sendEventToDaemon(type: String, payload: Any) {
        CommManager.sendEvent(type, payload)
    }

    /**
     * 请求唤醒UID。现在它只会调用 sendEventToDaemon，将请求放入队列。
     */
    private fun requestWakeupForUid(uid: Int, type: WakeupType) {
        // [核心重构] 检查UID是否被冻结的逻辑现在委托给 ConfigManager
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


    // --- 其他所有 Hook 方法保持不变 ---
    // 它们的内部逻辑（例如 hookActivitySwitchEvents）现在只会调用 CommManager.sendEvent，
    // 这是一个非常轻量级的操作，不会再创建线程或Socket。

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

    /**
     * [核心重构] ConfigManager (配置管理器)
     * 这是一个单例对象，负责存储从守护进程获取的配置。
     * 它的状态由CommManager的后台线程安全地更新。
     */
    private object ConfigManager {
        @Volatile var isBqHooked = false
        @Volatile private var frozenUids = emptySet<Int>()

        /**
         * 由 CommManager 的工作线程调用，用于更新配置。
         * @param jsonString 从守护进程收到的原始JSON响应字符串
         */
        fun updateConfig(jsonString: String) {
            try {
                // 解析完整的响应，并提取 'payload' 部分
                val payload = JsonParser.parseString(jsonString)
                    ?.asJsonObject?.getAsJsonObject("payload") ?: return

                if (payload.has("frozen_uids")) {
                    val uids = payload.getAsJsonArray("frozen_uids").map { it.asInt }.toSet()
                    frozenUids = uids // 原子性地替换整个Set
                }
                if (frozenUids.isNotEmpty()) {
                    log("Config updated. Now tracking ${frozenUids.size} frozen UIDs.")
                }
            } catch (e: Exception) {
                logError("Failed to parse probe config: $e")
            }
        }

        /**
         * 检查指定的UID是否处于被冻结状态。
         * 这是一个只读操作，是线程安全的。
         */
        fun isUidFrozen(uid: Int): Boolean = frozenUids.contains(uid)
    }

    private fun findClass(className: String, classLoader: ClassLoader): Class<*>? = XposedHelpers.findClassIfExists(className, classLoader)

    // 以下所有Hook方法的实现都保持不变，只是它们调用的 `sendEventToDaemon` 和 `ConfigManager.isUidFrozen`
    // 现在是新架构下的高效、安全的方法。

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