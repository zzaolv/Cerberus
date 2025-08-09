// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
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
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min

class ProbeHook : IXposedHookLoadPackage {

    private val gson = Gson()
    @Volatile private var nmsInstance: Any? = null
    @Volatile private var packageManager: PackageManager? = null

    companion object {
        private const val TAG = "CerberusProbe_v58_FsUDS" // 版本号更新
        private const val DAEMON_SOCKET_PATH = "/data/adb/cerberus/cerberusd.sock"
        const val FLAG_INCLUDE_STOPPED_PACKAGES = 32
        private const val USAGE_EVENT_ACTIVITY_RESUMED = 1
        private const val USAGE_EVENT_ACTIVITY_PAUSED = 2

        private fun log(message: String) = XposedBridge.log("[$TAG] $message")
        private fun logError(message: String) = XposedBridge.log("[$TAG] [ERROR] $message")
    }

    private enum class WakeupType(val value: Int) {
        GENERIC_NOTIFICATION(0),
        FCM_PUSH(1),
        PROACTIVE_START(2),
        BINDER_TRANSACTION(4),
        OTHER(3)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        log("Loading into system_server (PID: ${Process.myPid()}). HookSet active: $TAG")

        CommManager.start()

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
            log("INFO: Direct service startup blocking is disabled by configuration.")
            hookOomAdjustments(classLoader)
            hookPhantomProcessKiller(classLoader)
            hookExcessivePowerUsage(classLoader)
            hookMiuiGreezeManager(classLoader)
            hookOplusHansManager(classLoader)
        } catch (t: Throwable) {
            logError("CRITICAL: Failed during hook placement: $t")
        }
    }


    private object CommManager {
        private val eventQueue = LinkedBlockingQueue<Pair<String, Any>>()
        private var workerThread: Thread? = null
        private val gson = Gson()
        @Volatile private var isConfigInitialized = false
        private const val CONFIG_REFRESH_INTERVAL_MS = 5 * 60 * 1000L

        private fun performHandshake(): Boolean {
            try {
                log("Attempting handshake with daemon via UDS path ${DAEMON_SOCKET_PATH}...")
                val helloMessage = CerberusMessage(
                    type = "event.probe_hello",
                    payload = mapOf("pid" to Process.myPid(), "version" to TAG)
                )
                val jsonMessage = gson.toJson(helloMessage)

                LocalSocket().use { socket ->
                    val socketAddress = LocalSocketAddress(DAEMON_SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM)
                    socket.connect(socketAddress)

                    val writer = OutputStreamWriter(socket.outputStream, StandardCharsets.UTF_8)
                    writer.write(jsonMessage + "\n")
                    writer.flush()

                    val responseLine = socket.inputStream.bufferedReader(StandardCharsets.UTF_8).readLine()
                    if (responseLine != null) {
                        ConfigManager.updateConfig(responseLine)
                        log("Handshake successful. Probe config updated.")
                        return true
                    } else {
                        logError("Handshake failed: Daemon closed UDS connection prematurely.")
                    }
                }
            } catch (e: IOException) {
                if (e.message?.contains("ECONNREFUSED", ignoreCase = true) == false &&
                    e.message?.contains("No such file or directory", ignoreCase = true) == false) {
                    logError("Handshake IOException: ${e.message}.")
                } else {
                    log("Daemon UDS not ready during handshake attempt.")
                }
            } catch (t: Throwable) {
                logError("Handshake unhandled error: $t.")
            }
            return false
        }

        fun start() {
            if (workerThread?.isAlive == true) return
            workerThread = Thread {
                log("CommManager worker thread started.")

                var retryDelayMs = 2000L
                val maxDelayMs = 60000L
                while (!isConfigInitialized) {
                    if (performHandshake()) {
                        isConfigInitialized = true
                    } else {
                        try {
                            Thread.sleep(retryDelayMs)
                            retryDelayMs = min(retryDelayMs * 2, maxDelayMs)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }

                if (!isConfigInitialized) {
                    logError("CommManager failed to initialize. Thread is stopping.")
                    return@Thread
                }

                log("Initialization complete. Now processing event queue with reliable periodic config refresh.")
                var lastRefreshTime = System.currentTimeMillis()

                while (true) {
                    try {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastRefreshTime > CONFIG_REFRESH_INTERVAL_MS) {
                            log("Scheduled config refresh triggered by timer.")
                            if (performHandshake()) {
                                lastRefreshTime = currentTime
                            } else {
                                lastRefreshTime = currentTime - CONFIG_REFRESH_INTERVAL_MS + 60000L
                            }
                        }

                        val event = eventQueue.poll(1, TimeUnit.SECONDS)

                        if (event == null) {
                            continue
                        }

                        val (type, payload) = event
                        val message = CerberusMessage(type = type, payload = payload)
                        val jsonMessage = gson.toJson(message)

                        try {
                            LocalSocket().use { socket ->
                                val socketAddress = LocalSocketAddress(DAEMON_SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM)
                                socket.connect(socketAddress)
                                OutputStreamWriter(socket.outputStream, StandardCharsets.UTF_8).use { writer ->
                                    writer.write(jsonMessage + "\n")
                                    writer.flush()
                                }
                            }
                        } catch (e: IOException) {
                            if (e.message?.contains("ECONNREFUSED", ignoreCase = true) == false &&
                                e.message?.contains("No such file or directory", ignoreCase = true) == false) {
                                logError("Daemon short-conn send error for '$type': ${e.message}")
                            }
                        }
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (t: Throwable) {
                        logError("FATAL: Error in CommManager event loop: $t")
                        Thread.sleep(1000)
                    }
                }
                log("CommManager worker thread stopped.")
            }.apply {
                name = "CerberusCommThread"
                priority = Thread.NORM_PRIORITY - 1
                isDaemon = true
                start()
            }
        }

        fun sendEvent(type: String, payload: Any) {
            eventQueue.offer(type to payload)
        }
    }

    private object ConfigManager {
        @Volatile var isBqHooked = false
        @Volatile private var managedUids = emptySet<Int>()
        @Volatile private var frozenUids = emptySet<Int>()

        fun updateConfig(jsonString: String) {
            log("Received config JSON from daemon: $jsonString")
            try {
                val payload = JsonParser.parseString(jsonString)
                    ?.asJsonObject?.getAsJsonObject("payload") ?: run {
                    logError("Failed to parse config: payload is null or not an object.")
                    return
                }

                if (payload.has("managed_uids")) {
                    val uids = payload.getAsJsonArray("managed_uids").map { it.asInt }.toSet()
                    managedUids = uids
                    log("Config updated. Now managing ${managedUids.size} UIDs.")
                }

                if (payload.has("frozen_uids")) {
                    val uids = payload.getAsJsonArray("frozen_uids").map { it.asInt }.toSet()
                    frozenUids = uids
                    log("State updated. Now tracking ${frozenUids.size} frozen UIDs.")
                }

            } catch (e: Exception) {
                logError("Failed to parse probe config: $e")
            }
        }

        fun isUidManaged(uid: Int): Boolean = managedUids.contains(uid)
        fun isUidFrozen(uid: Int): Boolean = frozenUids.contains(uid)
    }

    private fun requestWakeupForUid(uid: Int, type: WakeupType) {
        if (uid >= Process.FIRST_APPLICATION_UID && ConfigManager.isUidFrozen(uid)) {
            val reason = when(type) {
                WakeupType.FCM_PUSH -> "FCM"
                WakeupType.GENERIC_NOTIFICATION -> "Notification"
                WakeupType.BINDER_TRANSACTION -> "Binder"
                else -> "Wakeup"
            }
            log("WAKEUP: Requesting temporary unfreeze for FROZEN UID $uid, Reason: $reason")
            sendEventToDaemon("event.app_wakeup_request_v2", mapOf("uid" to uid, "type_int" to type.value))
        }
    }

    // 省略所有未修改的hook函数，以保持简洁...
    private fun hookMiuiGreezeManager(classLoader: ClassLoader) {
        findClass("com.miui.server.greeze.GreezeManagerService", classLoader)?.let { clazz ->
            log("Found MIUI GreezeManagerService, attempting to hook.")
            try {
                val paramTypes = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    arrayOf(Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java, Boolean::class.java, Long::class.java, Int::class.java, Int::class.java)
                } else {
                    arrayOf(Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java, Boolean::class.java, Long::class.java, Int::class.java)
                }

                XposedHelpers.findAndHookMethod(clazz, "reportBinderTrans", *paramTypes, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val isOneway = param.args[5] as Boolean
                            if (isOneway) return

                            val dstUid = param.args[0] as Int
                            if (dstUid >= Process.FIRST_APPLICATION_UID) {
                                log("MIUI_COMPAT: reportBinderTrans for UID $dstUid. Requesting wakeup.")
                                requestWakeupForUid(dstUid, WakeupType.BINDER_TRANSACTION)
                            }
                        } catch (t: Throwable) {
                            logError("Error in MIUI GreezeManager hook: ${t.message}")
                        }
                    }
                })
                log("SUCCESS: Hooked MIUI GreezeManagerService#reportBinderTrans for compatibility.")
            } catch (t: Throwable) {
                logError("Failed to hook MIUI GreezeManagerService: ${t.message}")
            }
        } ?: log("INFO: MIUI GreezeManagerService not found (this is normal on non-MIUI systems).")
    }

    private fun hookOplusHansManager(classLoader: ClassLoader) {
        findClass("com.android.server.am.OplusHansManager", classLoader)?.let { clazz ->
            log("Found OplusHansManager, attempting to hook.")

            val unfreezeHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val type = param.args.find { it is Int && it == 1 } as? Int
                        if (type == null) {
                            return
                        }

                        var targetUid: Int? = null
                        for (arg in param.args) {
                            if (arg is Int && arg >= Process.FIRST_APPLICATION_UID) {
                                targetUid = arg
                                break
                            }
                        }

                        if (targetUid != null) {
                            log("OPLUS_COMPAT: unfreezeForKernel (Sync Binder) for UID $targetUid. Requesting wakeup.")
                            requestWakeupForUid(targetUid, WakeupType.BINDER_TRANSACTION)
                        }
                    } catch (t: Throwable) {
                        logError("Error in dynamic OplusHansManager hook: ${t.message}")
                    }
                }
            }

            var hookedCount = 0
            clazz.declaredMethods.filter { it.name == "unfreezeForKernel" }.forEach { method ->
                try {
                    XposedBridge.hookMethod(method, unfreezeHook)
                    hookedCount++
                } catch (t: Throwable) {
                    logError("Failed to hook a variant of unfreezeForKernel: ${t.message}")
                }
            }

            if (hookedCount > 0) {
                log("SUCCESS: Hooked $hookedCount variant(s) of OplusHansManager#unfreezeForKernel for compatibility.")
            } else {
                logError("Failed to hook any unfreezeForKernel method in OplusHansManager.")
            }

        } ?: log("INFO: OplusHansManager not found (this is normal on non-OplusOS systems).")
    }
    private fun hookPhantomProcessKiller(classLoader: ClassLoader) {
        findClass("com.android.server.am.PhantomProcessList", classLoader)?.let { clazz ->
            try {
                val method = clazz.declaredMethods.find { it.name == "updateProcessCpuStatesLocked" }
                if (method != null) {
                    XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
                    log("SUCCESS: Hooked and disabled PhantomProcessList#updateProcessCpuStatesLocked.")
                } else {
                    logError("WARN: Could not find PhantomProcessList#updateProcessCpuStatesLocked method.")
                }
            } catch (t: Throwable) {
                logError("Failed to hook PhantomProcessList: ${t.message}")
            }
        } ?: log("INFO: Did not find com.android.server.am.PhantomProcessList (normal on older Android).")
    }
    private fun hookExcessivePowerUsage(classLoader: ClassLoader) {
        findClass("com.android.server.am.ActivityManagerService", classLoader)?.let { clazz ->
            try {
                val method = clazz.declaredMethods.find { it.name == "checkExcessivePowerUsageLPr" }
                if (method != null) {
                    XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(false))
                    log("SUCCESS: Hooked and disabled AMS#checkExcessivePowerUsageLPr.")
                } else {
                    log("INFO: Did not find AMS#checkExcessivePowerUsageLPr method (may vary by ROM).")
                }
            } catch (t: Throwable) {
                logError("Failed to hook AMS#checkExcessivePowerUsageLPr: ${t.message}")
            }
        }
    }
    private fun hookOomAdjustments(classLoader: ClassLoader) {
        try {
            val processListClass = findClass("com.android.server.am.ProcessList", classLoader)
                ?: run {
                    logError("FATAL: Could not find com.android.server.am.ProcessList class!")
                    return
                }

            val setOomAdjHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val uid = param.args[1] as Int
                        if (uid < Process.FIRST_APPLICATION_UID) return
                        if (ConfigManager.isUidManaged(uid)) {
                            log("DEFENSE: System tried to set oom_adj of our MANAGED UID $uid. BLOCKED.")
                            param.result = null
                        }
                    } catch (t: Throwable) {
                        logError("Error in setOomAdj hook: ${t.message}")
                    }
                }
            }

            processListClass.declaredMethods.filter { it.name == "setOomAdj" }.forEach { method ->
                try {
                    XposedBridge.hookMethod(method, setOomAdjHook)
                    log("SUCCESS: Hooked ${method.name} in ProcessList for smart oom_adj defense.")
                } catch (t: Throwable) {
                    logError("Failed to hook a variant of setOomAdj: ${t.message}")
                }
            }

        } catch (t: Throwable) {
            logError("Could not hook OOM adjustments: ${t.message}")
        }
    }
    private fun sendEventToDaemon(type: String, payload: Any) { CommManager.sendEvent(type, payload) }
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
        val ignorableSystemActions = setOf(
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_TIME_TICK,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_CONFIGURATION_CHANGED,
            "android.net.conn.CONNECTIVITY_CHANGE"
        )
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val record = param.args.firstOrNull { it != null && brClass.isInstance(it) } ?: return
                    val intent = XposedHelpers.getObjectField(record, "intent") as? Intent ?: return
                    val receiversList = XposedHelpers.getObjectField(record, "receivers") as? ArrayList<*> ?: return
                    if (isGcmOrFcmIntent(intent)) {
                        intent.flags = intent.flags or FLAG_INCLUDE_STOPPED_PACKAGES
                        receiversList.forEach { receiver ->
                            try {
                                val uid = getUidFromReceiver(receiver)
                                uid?.let { requestWakeupForUid(it, WakeupType.FCM_PUSH) }
                            } catch (ignored: Throwable) {}
                        }
                        return
                    }
                    val action = intent.action
                    if (action in ignorableSystemActions) {
                        val originalSize = receiversList.size
                        if (originalSize == 0) return
                        receiversList.removeIf { receiver ->
                            try {
                                val uid = getUidFromReceiver(receiver)
                                if (uid != null && ConfigManager.isUidManaged(uid)) {
                                    log("FILTER: Blocking broadcast '$action' to MANAGED UID $uid.")
                                    return@removeIf true
                                }
                            } catch (ignored: Throwable) {}
                            false
                        }
                        if (receiversList.size < originalSize) {
                            log("Filtered ${originalSize - receiversList.size} managed receivers for broadcast '$action'.")
                            if (receiversList.isEmpty()) {
                                log("All receivers for '$action' were managed. Aborting broadcast.")
                                param.result = null
                            }
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
                    log("SUCCESS: Hooked ${concreteBqClass.simpleName}#${it.name} for broadcast filtering & wakeup.")
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
    private fun getUidFromReceiver(receiver: Any?): Int? {
        if (receiver == null) return null
        (XposedHelpers.getObjectField(receiver, "app") as? Any)?.let { processRecord ->
            return XposedHelpers.getIntField(processRecord, "uid")
        }
        (receiver as? ResolveInfo)?.activityInfo?.applicationInfo?.uid?.let {
            return it
        }
        return null
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
                    if (ConfigManager.isUidManaged(targetUid)) {
                        val channel = try {
                            nmsInstance?.let { XposedHelpers.callMethod(it, "getNotificationChannelForPackage", pkg, targetUid, notification.channelId, false) as? NotificationChannel }
                        } catch (t: Throwable) { null }
                        if (channel != null && channel.importance < NotificationManager.IMPORTANCE_DEFAULT) {
                            log("Ignoring low-importance notification for managed UID $targetUid")
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
                    if (ConfigManager.isUidManaged(XposedHelpers.getIntField(processRecord, "uid"))) {
                        log("PROTECT: Suppressing ANR for managed UID: ${XposedHelpers.getIntField(processRecord, "uid")}")
                        param.result = null
                    }
                }
            }
            clazz.declaredMethods.filter { it.name.contains("appNotResponding") }.forEach { XposedBridge.hookMethod(it, anrHook) }
            log("SUCCESS: Hooked all ANR methods in AnrHelper as a fallback.")
        }
    }
    private fun hookWakelocksAndAlarms(classLoader: ClassLoader) {
        findClass("com.android.server.power.PowerManagerService", classLoader)?.let { pmsClass ->
            val wlHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val uidArg = param.args.find { it is Int && it >= Process.FIRST_APPLICATION_UID } as? Int
                    if (uidArg != null && ConfigManager.isUidManaged(uidArg)) {
                        log("DEFENSE: Blocked acquireWakeLock for managed uid: $uidArg")
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
                        ConfigManager.isUidManaged(XposedHelpers.getIntField(alarm!!, "uid")).also {
                            if (it) log("DEFENSE: Blocked alarm for managed UID: ${XposedHelpers.getIntField(alarm, "uid")}")
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
    private fun isGcmOrFcmIntent(intent: Intent): Boolean {
        return intent.action?.let { it == "com.google.android.c2dm.intent.RECEIVE" || it == "com.google.firebase.MESSAGING_EVENT" } ?: false
    }
    private data class AppInstanceKey(val package_name: String, val user_id: Int)
}