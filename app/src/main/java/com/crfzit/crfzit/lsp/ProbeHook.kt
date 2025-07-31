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
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val unfreezeRequestTimestamps = ConcurrentHashMap<String, Long>()
    private val THROTTLE_INTERVAL_MS = 5000

    companion object {
        private const val TAG = "CerberusProbe_v26_Final"
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
                // Connection refused is expected at boot, don't spam the log
                if (e.message?.contains("ECONNREFUSED") != true) {
                    logError("Daemon short-conn send error for $type: ${e.message}")
                }
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
                }
            } catch (e: Exception) { 
                val errorMsg = "Failed to parse probe config: $e"
                XposedBridge.log("[$TAG]: [ERROR] $errorMsg") 
            }
        }
        fun isUidFrozen(uid: Int): Boolean = frozenUids.contains(uid)
    }

    private fun log(message: String) = XposedBridge.log("[$TAG] $message")
    private fun logError(message: String) = XposedBridge.log("[$TAG] [ERROR] $message")

    private fun findClass(className: String, classLoader: ClassLoader): Class<*>? {
        return XposedHelpers.findClassIfExists(className, classLoader)
    }

    private fun hookActivitySwitchEvents(classLoader: ClassLoader) {
        findClass("com.android.server.am.ActivityManagerService", classLoader)?.let { clazz ->
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val componentName = param.args[0] as? ComponentName ?: return
                        val userId = param.args[1] as Int
                        val event = param.args[2] as Int
                        
                        val packageName = componentName.packageName
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

    /**
     * [增强Hook 2 - 修正] 拦截向已冻结应用分发广播。
     * 目标: com.android.server.am.BroadcastQueueModernImpl.dispatchReceivers
     * 作用: 防止因冻结导致应用无法处理广播而引发的ANR。
     */
    private fun hookBroadcastDelivery(classLoader: ClassLoader) {
        findClass("com.android.server.am.BroadcastQueueModernImpl", classLoader)?.let { clazz ->
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        // 第一个参数是 BroadcastProcessQueue
                        val queue = param.args[0]
                        val app = XposedHelpers.getObjectField(queue, "app") as? Any // ProcessRecord
                        if (app != null) {
                            val uid = XposedHelpers.getIntField(app, "uid")
                            if (ConfigManager.isUidFrozen(uid)) {
                                val pkg = (XposedHelpers.getObjectField(app, "info") as ApplicationInfo).packageName
                                // 返回false，让调用者认为分发“瞬间”完成，从而安全跳过
                                param.result = false
                                log("DEFENSE: Skipped broadcast dispatch to frozen app: $pkg (uid: $uid)")
                            }
                        }
                    } catch (t: Throwable) {
                        logError("Error in hookBroadcastDelivery: ${t.message}")
                    }
                }
            }

            clazz.declaredMethods.find { it.name == "dispatchReceivers" && it.parameterCount == 3 }
                ?.let {
                    XposedBridge.hookMethod(it, hook)
                    log("SUCCESS: Hooked BroadcastQueueModernImpl#dispatchReceivers to prevent broadcast ANRs.")
                } ?: logError("WARN: Could not find BroadcastQueueModernImpl#dispatchReceivers method.")
        } ?: logError("WARN: Could not find com.android.server.am.BroadcastQueueModernImpl class.")
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