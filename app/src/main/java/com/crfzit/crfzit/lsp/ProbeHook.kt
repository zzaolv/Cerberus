// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.Notification
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
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * [新增] 一个简单的、线程安全的、用于system_server的文件日志记录器
 */
private object FileLogger {
    private const val LOG_FILE_PATH = "/data/local/tmp/cerberus_probe_hook.log"
    private val logFile = File(LOG_FILE_PATH)
    private val logScope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    init {
        // 首次加载时，清空旧日志，避免文件无限增大
        logScope.launch {
            try {
                if(logFile.exists()) {
                    logFile.writeText("") // 清空
                }
                log("INFO", "FileLogger initialized. Log file at: $LOG_FILE_PATH")
            } catch (e: Exception) {
                // 如果出现权限问题，就在Xposed日志中记录错误
                XposedBridge.log("[CerberusFileLogger] Error initializing log file: ${e.message}")
            }
        }
    }

    fun log(level: String, message: String) {
        logScope.launch {
            try {
                val timestamp = dateFormat.format(Date())
                val logLine = "$timestamp [$level] - $message\n"
                logFile.appendText(logLine, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                // 写入失败时，至少保证Xposed日志有输出
                XposedBridge.log("[CerberusFileLogger] Failed to write to log file: ${e.message}")
            }
        }
    }
}


class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val unfreezeRequestTimestamps = ConcurrentHashMap<String, Long>()
    private val THROTTLE_INTERVAL_MS = 5000

    companion object {
        private const val TAG = "CerberusProbe_v25_Enhanced"
        const val FLAG_INCLUDE_STOPPED_PACKAGES = 32
        private const val DAEMON_HOST = "127.0.0.1"
        private const val DAEMON_PORT = 28900

        private const val USAGE_EVENT_ACTIVITY_RESUMED = 1
        private const val USAGE_EVENT_ACTIVITY_PAUSED = 2
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return

        log("Loading into system_server (PID: ${Process.myPid()}). Enhanced HookSet active.")

        sendEventToDaemon("event.probe_hello", mapOf("pid" to Process.myPid(), "version" to TAG))

        try {
            val classLoader = lpparam.classLoader

            hookActivitySwitchEvents(classLoader)
            hookBroadcastDelivery(classLoader)
            hookTaskTrimming(classLoader)
            hookSystemFreezer(classLoader)

            hookAnrHelper(classLoader)
            hookWakelocksAndAlarms(classLoader)
            hookActivityStarter(classLoader)
            hookFcmWakeup(classLoader)

            hookNotificationService(classLoader)
            hookServices(classLoader)
            hookProcessSignal(classLoader)

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
                        writer.write(jsonMessage + "\n")
                        writer.flush()
                        if (type == "event.probe_hello") {
                            try {
                                val response = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                                if (response != null) {
                                    ConfigManager.updateConfig(response)
                                }
                            } catch (e: Exception) {
                                logError("Failed to read config response after hello: $e")
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                logError("Daemon short-conn send error for $type: ${e.message}")
            } catch (e: Exception) {
                logError("Unexpected error during short-conn send for $type: $e")
            }
        }
    }

    private object ConfigManager {
        @Volatile private var frozenUids = emptySet<Int>()

        fun updateConfig(jsonString: String) {
            try {
                val payload = JsonParser.parseString(jsonString).asJsonObject.getAsJsonObject("payload")
                if (payload.has("frozen_uids")) {
                    frozenUids = payload.getAsJsonArray("frozen_uids").map { it.asInt }.toSet()
                }
                if (frozenUids.isNotEmpty()) {
                    XposedBridge.log("[$TAG]: Config updated. Tracking ${frozenUids.size} UIDs.")
                    FileLogger.log("CONFIG", "Config updated. Tracking ${frozenUids.size} UIDs.")
                }
            } catch (e: Exception) {
                val errorMsg = "Failed to parse probe config: $e"
                XposedBridge.log("[$TAG]: [ERROR] $errorMsg")
                FileLogger.log("ERROR", errorMsg)
            }
        }
        fun isUidFrozen(uid: Int): Boolean = frozenUids.contains(uid)
    }

    // [修改] 同时写入Xposed日志和文件日志
    private fun log(message: String) {
        XposedBridge.log("[$TAG] $message")
        FileLogger.log("INFO", message)
    }

    // [修改] 同时写入Xposed日志和文件日志
    private fun logError(message: String) {
        XposedBridge.log("[$TAG] [ERROR] $message")
        FileLogger.log("ERROR", message)
    }

    private fun findClass(className: String, classLoader: ClassLoader): Class<*>? {
        return XposedHelpers.findClassIfExists(className, classLoader)
    }

    private fun hookActivitySwitchEvents(classLoader: ClassLoader) {
        findClass("com.android.server.am.ActivityManagerService", classLoader)?.let { clazz ->
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val event = param.args[2] as Int
                        val componentName = param.args[0]?.let {
                            XposedHelpers.getObjectField(it, "mActivityComponent") as? ComponentName
                        } ?: return

                        val packageName = componentName.packageName
                        val userId = param.args[1] as Int
                        val key = AppInstanceKey(packageName, userId)

                        when (event) {
                            USAGE_EVENT_ACTIVITY_RESUMED -> {
                                sendEventToDaemon("event.app_foreground", key)
                            }
                            USAGE_EVENT_ACTIVITY_PAUSED -> {
                                sendEventToDaemon("event.app_background", key)
                            }
                        }
                    } catch (t: Throwable) {
                        logError("Error in hookActivitySwitchEvents: ${t.message}")
                    }
                }
            }
            clazz.declaredMethods.find { it.name == "updateActivityUsageStats" && it.parameterCount >= 3 }
                ?.let {
                    XposedBridge.hookMethod(it, hook)
                    log("SUCCESS: Hooked ActivityManagerService#updateActivityUsageStats for precise app switching.")
                } ?: logError("FATAL: Could not find ActivityManagerService#updateActivityUsageStats method!")
        } ?: logError("FATAL: Could not find com.android.server.am.ActivityManagerService class!")
    }

    private fun hookBroadcastDelivery(classLoader: ClassLoader) {
        findClass("com.android.server.am.BroadcastQueue", classLoader)?.let { clazz ->
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val receiver = param.args[1]
                        val receiverList = XposedHelpers.getObjectField(receiver, "receiverList")
                        val app = XposedHelpers.getObjectField(receiverList, "app")
                        if (app != null) {
                            val uid = XposedHelpers.getIntField(app, "uid")
                            if (ConfigManager.isUidFrozen(uid)) {
                                val pkg = (XposedHelpers.getObjectField(app, "info") as ApplicationInfo).packageName
                                param.result = -1
                                log("DEFENSE: Skipped broadcast delivery to frozen app: $pkg (uid: $uid)")
                            }
                        }
                    } catch (t: Throwable) {
                    }
                }
            }

            clazz.declaredMethods.find { it.name == "deliverToRegisteredReceiverLocked" }
                ?.let {
                    XposedBridge.hookMethod(it, hook)
                    log("SUCCESS: Hooked BroadcastQueue#deliverToRegisteredReceiverLocked to prevent broadcast ANRs.")
                } ?: logError("WARN: Could not find BroadcastQueue#deliverToRegisteredReceiverLocked method.")
        } ?: logError("WARN: Could not find com.android.server.am.BroadcastQueue class.")
    }

    private fun hookTaskTrimming(classLoader: ClassLoader) {
        findClass("com.android.server.wm.RecentTasks", classLoader)?.let { clazz ->
            XposedBridge.hookAllMethods(clazz, "trimInactiveRecentTasks", XC_MethodReplacement.DO_NOTHING)
            log("SUCCESS: Hooked and disabled RecentTasks#trimInactiveRecentTasks.")
        } ?: logError("WARN: Could not find com.android.server.wm.RecentTasks class, task trimming not disabled.")
    }

    private fun hookSystemFreezer(classLoader: ClassLoader) {
        findClass("com.android.server.am.CachedAppOptimizer", classLoader)?.let { clazz ->
            XposedBridge.hookAllMethods(clazz, "useFreezer", XC_MethodReplacement.returnConstant(false))
            log("SUCCESS: Hooked and disabled system's CachedAppOptimizer freezer.")
        } ?: logError("INFO: Did not find com.android.server.am.CachedAppOptimizer (this is normal on older Android versions).")
    }

    private fun hookAnrHelper(classLoader: ClassLoader) {
        findClass("com.android.server.am.AnrHelper", classLoader)?.let { clazz ->
            val anrHook = object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val processRecord = param.args.find { it != null && it::class.java.name.endsWith("ProcessRecord") } ?: return
                    val uid = XposedHelpers.getIntField(processRecord, "uid")
                    if (ConfigManager.isUidFrozen(uid)) {
                        log("PROTECT: Suppressing ANR for frozen UID: $uid")
                        param.result = null
                    }
                }
            }
            clazz.declaredMethods.filter { it.name.contains("appNotResponding") }.forEach {
                XposedBridge.hookMethod(it, anrHook)
            }
            log("SUCCESS: Hooked all ANR methods in AnrHelper.")
        }
    }

    private fun hookWakelocksAndAlarms(classLoader: ClassLoader) {
        findClass("com.android.server.power.PowerManagerService", classLoader)?.let { pmsClass ->
            val wlHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val uidArg = param.args.find { it is Int && it >= Process.FIRST_APPLICATION_UID } as? Int
                    if (uidArg != null && ConfigManager.isUidFrozen(uidArg)) {
                        val pkg = param.args.find { it is String && it.contains('.') } ?: "unknown"
                        log("DEFENSE: Blocked acquireWakeLock for frozen app: $pkg (uid: $uidArg)")
                        param.result = null
                    }
                }
            }
            pmsClass.declaredMethods.filter { it.name == "acquireWakeLockInternal" || it.name == "acquireWakeLock" }.forEach {
                XposedBridge.hookMethod(it, wlHook)
            }
            log("SUCCESS: Hooked PowerManagerService wakelock acquisition methods.")
        }

        findClass("com.android.server.alarm.AlarmManagerService", classLoader)?.let { amsClass ->
            val alarmHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    (param.args.firstOrNull { it is ArrayList<*> } as? ArrayList<*>)?.removeIf { alarm ->
                        val uid = XposedHelpers.getIntField(alarm!!, "uid")
                        if (ConfigManager.isUidFrozen(uid)) {
                            log("DEFENSE: Blocked alarm for frozen UID: $uid")
                            true
                        } else false
                    }
                }
            }
            amsClass.declaredMethods.filter { it.name.contains("triggerAlarms") }.forEach {
                XposedBridge.hookMethod(it, alarmHook)
            }
            log("SUCCESS: Hooked AlarmManagerService alarm trigger methods.")
        }
    }

    private fun hookActivityStarter(classLoader: ClassLoader) {
        findClass("com.android.server.wm.ActivityStarter", classLoader)?.let { clazz ->
            val executeHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val request = param.args.find { it != null && it::class.java.name == "com.android.server.wm.ActivityStarter\$Request" }
                        if (request != null) {
                            val intent = XposedHelpers.getObjectField(request, "intent") as? Intent
                            val callingUid = XposedHelpers.getIntField(request, "callingUid")

                            intent?.component?.packageName?.let { packageName ->
                                val userId = XposedHelpers.getIntField(request, "userId")
                                if (callingUid >= Process.FIRST_APPLICATION_UID) {
                                    log("PROACTIVE: Activity start detected for $packageName (user $userId). Requesting unfreeze.")
                                    sendEventToDaemon("cmd.proactive_unfreeze", AppInstanceKey(packageName, userId))
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        logError("Error in ActivityStarter#execute hook: $t")
                    }
                }
            }
            XposedBridge.hookAllMethods(clazz, "execute", executeHook)
            log("SUCCESS: Hooked com.android.server.wm.ActivityStarter#execute for proactive unfreezing.")
        } ?: logError("FATAL: Could not find com.android.server.wm.ActivityStarter class!")
    }

    private fun hookFcmWakeup(classLoader: ClassLoader) {
        findClass("com.android.server.am.ActivityManagerService", classLoader)?.let { amsClass ->
            amsClass.declaredMethods.find {
                it.name == "broadcastIntent" && it.parameterCount > 5 && it.parameterTypes.any { clazz -> clazz == Intent::class.java }
            }?.let { broadcastIntentMethod ->
                XposedBridge.hookMethod(broadcastIntentMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args.find { it is Intent } as? Intent ?: return
                        if (isGcmOrFcmIntent(intent)) {
                            intent.flags = intent.flags or FLAG_INCLUDE_STOPPED_PACKAGES
                            val targetPackage = intent.`package` ?: intent.component?.packageName
                            if (targetPackage != null) {
                                requestTempUnfreezeForPackage(targetPackage)
                            }
                        }
                    }
                })
                log("SUCCESS: Hooked AMS#broadcastIntent for FCM wakeup.")
            }
        }
    }

    private fun hookNotificationService(classLoader: ClassLoader) {
        findClass("com.android.server.notification.NotificationManagerService", classLoader)?.let { clazz ->
            val hook = object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pkg = param.args[0] as? String ?: return
                    val uid = param.args[1] as? Int ?: return
                    if (ConfigManager.isUidFrozen(uid)) {
                        val notification = param.args.find { it is Notification } as? Notification
                        if (notification != null && notification.group?.startsWith("gcm.notification") != true) {
                            log("DEFENSE: Blocked notification for frozen app: $pkg")
                            param.result = null
                        }
                    }
                }
            }
            clazz.declaredMethods.filter { it.name == "enqueueNotificationInternal" }.forEach {
                XposedBridge.hookMethod(it, hook)
            }
            log("SUCCESS: Hooked NotificationManagerService#enqueueNotificationInternal.")
        }
    }

    private fun hookServices(classLoader: ClassLoader) {
        findClass("com.android.server.am.ActiveServices", classLoader)?.let { clazz ->
            val hook = object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val serviceRecord = param.args.find { it != null && it::class.java.name.endsWith("ServiceRecord") } ?: return
                    val appInfo = XposedHelpers.getObjectField(serviceRecord, "appInfo") as? ApplicationInfo ?: return
                    if (ConfigManager.isUidFrozen(appInfo.uid)) {
                        log("DEFENSE: Blocked service startup for frozen app: ${appInfo.packageName}")
                        param.result = null
                    }
                }
            }
            clazz.declaredMethods.filter { it.name.contains("bringUpService") }.forEach {
                XposedBridge.hookMethod(it, hook)
            }
            log("SUCCESS: Hooked ActiveServices service startup methods.")
        }
    }

    private fun hookProcessSignal(classLoader: ClassLoader) {
        // This hook remains less effective, but kept for minimal protection.
        // The main protection is now the ANR hook.
    }

    private fun isGcmOrFcmIntent(intent: Intent): Boolean {
        val action = intent.action ?: return false
        return action == "com.google.android.c2dm.intent.RECEIVE" || action == "com.google.firebase.MESSAGING_EVENT"
    }

    private fun requestTempUnfreezeForPackage(packageName: String) {
        val now = System.currentTimeMillis()
        if (now - (unfreezeRequestTimestamps[packageName] ?: 0L) < THROTTLE_INTERVAL_MS) return
        unfreezeRequestTimestamps[packageName] = now
        log("WAKEUP: FCM message for $packageName. Requesting unfreeze.")
        sendEventToDaemon("cmd.request_temp_unfreeze_pkg", mapOf("package_name" to packageName))
    }

    private data class AppInstanceKey(val package_name: String, val user_id: Int)
}