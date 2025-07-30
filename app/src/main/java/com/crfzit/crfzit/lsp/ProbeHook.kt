// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.Notification
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.UserHandle
import com.crfzit.crfzit.data.model.CerberusMessage
import com.crfzit.crfzit.data.uds.TcpClient // [核心修复] 引入 TcpClient
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
import kotlin.coroutines.coroutineContext

@OptIn(DelicateCoroutinesApi::class)
class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    // [核心修复] 变量类型和名称同步更新
    private var tcpClient: TcpClient? = null
    private val gson = Gson()

    private val foregroundStatusCache = ConcurrentHashMap<Int, Boolean>()
    private val unfreezeRequestTimestamps = ConcurrentHashMap<String, Long>()
    private val THROTTLE_INTERVAL_MS = 5000

    companion object {
        private const val TAG = "CerberusProbe_v22_TCP" // 更新版本号
        const val FLAG_INCLUDE_STOPPED_PACKAGES = 32
        private const val PER_USER_RANGE = 100000
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return

        log("Loading into system_server (PID: ${Process.myPid()}).")
        // [核心修复] 实例化 TcpClient
        tcpClient = TcpClient(probeScope)
        probeScope.launch { setupPersistentUdsCommunication() }

        try {
            val classLoader = lpparam.classLoader

            hookActivityStarter(classLoader)
            hookAMSForProcessStateChanges(classLoader)
            hookWakelocksAndAlarms(classLoader)
            hookServicesAndBroadcasts(classLoader)
            hookNotificationService(classLoader)
            hookProcessSignal(classLoader)
            hookAnrHelper(classLoader)
            hookMediaAndAudio(classLoader)
        } catch (t: Throwable) {
            logError("CRITICAL: Failed during hook placement: $t")
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
                                    sendEventToDaemon("cmd.proactive_unfreeze", mapOf("package_name" to packageName, "user_id" to userId))
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


    private fun findClass(className: String, classLoader: ClassLoader): Class<*>? {
        return XposedHelpers.findClassIfExists(className, classLoader)
    }

    private fun findAndHookMethod(
        clazz: Class<*>?,
        methodName: String,
        hook: XC_MethodHook,
        vararg parameterTypes: Any
    ) {
        if (clazz == null) {
            logError("Cannot hook $methodName because class is null.")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, *parameterTypes, hook)
            log("SUCCESS: Hooked ${clazz.simpleName}#$methodName.")
        } catch (t: Throwable) {
            logError("Failed to hook ${clazz.simpleName}#$methodName with specific signature: $t")
        }
    }

    private fun hookAMSForProcessStateChanges(classLoader: ClassLoader) {
        val processRecordClass = findClass("com.android.server.am.ProcessRecord", classLoader)
        if (processRecordClass == null) {
            logError("FATAL: Could not find com.android.server.am.ProcessRecord class!")
            return
        }

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                handleProcessStateChange(param.thisObject)
            }
        }

        try {
            XposedHelpers.findAndHookMethod(processRecordClass, "setCurProcState", Int::class.javaPrimitiveType, hook)
            log("SUCCESS: Hooked ProcessRecord#setCurProcState(int).")
        } catch (e: NoSuchMethodError) {
            logError("FATAL: Could not find ProcessRecord#setCurProcState(int). Process state changes will not be detected! Error: $e")
        } catch(t: Throwable) {
            logError("FATAL: An unexpected error occurred while hooking ProcessRecord#setCurProcState: $t")
        }
    }


    private fun handleProcessStateChange(processRecord: Any) {
        val appInfo = XposedHelpers.getObjectField(processRecord, "info") as? ApplicationInfo ?: return
        val uid = appInfo.uid
        if (uid < Process.FIRST_APPLICATION_UID) return

        val adj = XposedHelpers.getIntField(processRecord, "mCurAdj")
        val procState = XposedHelpers.getIntField(processRecord, "mCurProcState")
        val isForeground = adj <= 200 || procState == 2

        if (foregroundStatusCache.put(uid, isForeground) == isForeground) return

        val packageName = appInfo.packageName
        val userId = uid / PER_USER_RANGE
        val eventType = if (isForeground) "event.app_foreground" else "event.app_background"
        log("EVENT: App ${packageName}(${userId}) -> ${if(isForeground) "foreground" else "background"}")
        sendEventToDaemon(eventType, mapOf("package_name" to packageName, "user_id" to userId))
    }

    private fun findAndHookMethodFuzzy(
        clazz: Class<*>?,
        methodName: String,
        hook: XC_MethodHook
    ) {
        if (clazz == null) return
        var hooked = false
        clazz.declaredMethods
            .filter { it.name == methodName }
            .forEach {
                XposedBridge.hookMethod(it, hook)
                hooked = true
            }
        if (hooked) {
            log("SUCCESS: Hooked ${clazz.simpleName}#$methodName (fuzzy).")
        }
    }


    private fun hookWakelocksAndAlarms(classLoader: ClassLoader) {
        findAndHookMethodFuzzy(findClass("com.android.server.power.PowerManagerService", classLoader), "acquireWakeLockInternal", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val uid = param.args.find { it is Int && it >= Process.FIRST_APPLICATION_UID } as? Int ?: return
                if (ConfigManager.isUidFrozen(uid)) {
                    val packageName = param.args.find { it is String && it.contains('.') } as? String ?: "unknown"
                    log("DEFENSE: Blocked acquireWakeLock for frozen app: $packageName (uid: $uid)")
                    param.result = null
                }
            }
        })

        findClass("com.android.server.alarm.AlarmManagerService", classLoader)?.let { clazz ->
            XposedBridge.hookAllMethods(clazz, "triggerAlarmsLocked", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    (param.args.firstOrNull { it is ArrayList<*> } as? ArrayList<*>)?.removeIf { alarm ->
                        val uid = XposedHelpers.getIntField(alarm!!, "uid")
                        if (ConfigManager.isUidFrozen(uid)) {
                            log("DEFENSE: Blocked alarm for frozen UID: $uid")
                            true
                        } else false
                    }
                }
            })
            log("SUCCESS: Hooked AlarmManagerService#triggerAlarmsLocked (all overloads).")
        }
    }

    private fun hookServicesAndBroadcasts(classLoader: ClassLoader) {
        findAndHookMethodFuzzy(findClass("com.android.server.am.ActiveServices", classLoader), "bringUpServiceLocked", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val serviceRecord = param.args.find { it != null && it::class.java.name.endsWith("ServiceRecord") } ?: return
                val appInfo = XposedHelpers.getObjectField(serviceRecord, "appInfo") as? ApplicationInfo ?: return
                if (ConfigManager.isUidFrozen(appInfo.uid)) {
                    log("DEFENSE: Blocked service startup for frozen app: ${appInfo.packageName}")
                    param.result = null
                }
            }
        })
    }

    private fun hookNotificationService(classLoader: ClassLoader) {
        findAndHookMethodFuzzy(findClass("com.android.server.notification.NotificationManagerService", classLoader), "enqueueNotificationInternal", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val uid = param.args.find { it is Int } as? Int ?: return
                if (ConfigManager.isUidFrozen(uid)) {
                    val pkg = param.args.find { it is String } as? String ?: "unknown"
                    val notification = param.args.find { it is Notification } as? Notification
                    if (notification != null && notification.group?.startsWith("gcm.notification") != true) {
                        log("DEFENSE: Blocked notification for frozen app: $pkg")
                        param.result = null
                    }
                }
            }
        })
    }

    private fun hookProcessSignal(classLoader: ClassLoader) {
        val hook = object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val pid = param.args[0] as Int
                val signal = param.args[1] as Int
                if (signal == 9 && ConfigManager.isPidFrozen(pid)) {
                    log("PROTECT: Intercepted SIGKILL for frozen PID: $pid. Thawing.")
                    requestTempUnfreezeForPid(pid)
                    param.result = null
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(Process::class.java, "sendSignal", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, hook)
            XposedHelpers.findAndHookMethod(Process::class.java, "sendSignalQuiet", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, hook)
            log("SUCCESS: Hooked Process#sendSignal and sendSignalQuiet.")
        } catch (t: Throwable) {
            logError("Failed to hook Process#sendSignal: $t")
        }
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

    private fun hookMediaAndAudio(classLoader: ClassLoader) {
        findAndHookMethodFuzzy(findClass("com.android.server.audio.FocusRequester", classLoader), "handleFocusGain", object: XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val uid = XposedHelpers.getIntField(param.thisObject, "mCallingUid")
                if (ConfigManager.isUidFrozen(uid)) {
                    log("WAKEUP: App UID $uid gained audio focus. Requesting unfreeze.")
                    requestTempUnfreezeForUid(uid)
                }
            }
        })

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

    private fun requestTempUnfreezeForUid(uid: Int) {
        sendEventToDaemon("cmd.request_temp_unfreeze_uid", mapOf("uid" to uid))
    }

    private fun requestTempUnfreezeForPid(pid: Int) {
        sendEventToDaemon("cmd.request_temp_unfreeze_pid", mapOf("pid" to pid))
    }

    private fun sendEventToDaemon(type: String, payload: Any) {
        probeScope.launch {
            try {
                val message = CerberusMessage(type = type, payload = payload)
                // [核心修复] 使用 tcpClient 发送消息
                tcpClient?.sendMessage(gson.toJson(message))
            } catch (e: Exception) {
                logError("Daemon send error for $type: $e")
            }
        }
    }

    private suspend fun setupPersistentUdsCommunication() {
        log("Persistent communication manager started.")
        while (coroutineContext.isActive) {
            try {
                // [核心修复] 调用 tcpClient 的方法
                tcpClient?.start()
                delay(1000)
                val helloPayload = mapOf("pid" to Process.myPid(), "version" to TAG)
                tcpClient?.sendMessage(gson.toJson(CerberusMessage(type = "event.probe_hello", payload = helloPayload)))
                tcpClient?.incomingMessages?.collect { jsonLine -> ConfigManager.updateConfig(jsonLine) }
                logError("TCP message stream ended. Reconnecting...")
                tcpClient?.stop()
            } catch (e: CancellationException) {
                log("Communication scope cancelled."); break
            } catch (e: Exception) {
                logError("Exception in communication cycle: ${e.message}. Retrying...")
            }
            delay(5000L)
        }
    }

    private object ConfigManager {
        @Volatile private var frozenUids = emptySet<Int>()
        @Volatile private var frozenPids = emptySet<Int>()

        fun updateConfig(jsonString: String) {
            try {
                val payload = JsonParser.parseString(jsonString).asJsonObject.getAsJsonObject("payload")
                if (payload.has("frozen_uids")) {
                    frozenUids = payload.getAsJsonArray("frozen_uids").map { it.asInt }.toSet()
                }
                if (payload.has("frozen_pids")) {
                    frozenPids = payload.getAsJsonArray("frozen_pids").map { it.asInt }.toSet()
                    if (frozenUids.isNotEmpty() || frozenPids.isNotEmpty()) {
                        XposedBridge.log("[$TAG]: Config updated. Tracking ${frozenUids.size} UIDs and ${frozenPids.size} PIDs.")
                    }
                }
            } catch (e: Exception) { XposedBridge.log("[$TAG]: [ERROR] Failed to parse probe config: $e") }
        }
        fun isUidFrozen(uid: Int): Boolean = frozenUids.contains(uid)
        fun isPidFrozen(pid: Int): Boolean = frozenPids.contains(pid)
    }

    private fun log(message: String) = XposedBridge.log("[$TAG] $message")
    private fun logError(message: String) = XposedBridge.log("[$TAG] [ERROR] $message")
}