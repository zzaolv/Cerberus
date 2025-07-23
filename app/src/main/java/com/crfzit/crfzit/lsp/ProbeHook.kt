// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.Notification
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import com.crfzit.crfzit.data.model.CerberusMessage
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
import kotlin.coroutines.coroutineContext

@OptIn(DelicateCoroutinesApi::class)
class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private var udsClient: UdsClient? = null
    private val gson = Gson()

    private val foregroundStatusCache = ConcurrentHashMap<Int, Boolean>()
    private val unfreezeRequestTimestamps = ConcurrentHashMap<String, Long>()
    private val THROTTLE_INTERVAL_MS = 5000 // 5秒节流

    companion object {
        private const val TAG = "CerberusProbe_v15_Ultimate"
        const val FLAG_INCLUDE_STOPPED_PACKAGES = 32
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return

        log("Loading into system_server (PID: ${Process.myPid()}).")
        udsClient = UdsClient(probeScope)
        probeScope.launch { setupPersistentUdsCommunication() }

        try {
            val classLoader = lpparam.classLoader
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

    // --- 健壮的方法/类查找器 ---
    private fun findClass(className: String, classLoader: ClassLoader): Class<*>? {
        return XposedHelpers.findClassIfExists(className, classLoader)
    }

    private fun findAndHookMethod(
        clazz: Class<*>?,
        methodName: String,
        hook: XC_MethodHook,
        vararg parameterTypes: Any
    ) {
        if (clazz == null) return
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, *parameterTypes, hook)
            log("SUCCESS: Hooked ${clazz.simpleName}#$methodName (exact match).")
        } catch (e: NoSuchMethodError) {
            // 模糊匹配：查找所有同名方法，尝试匹配参数数量
            var hooked = false
            clazz.declaredMethods
                .filter { it.name == methodName && it.parameterCount >= parameterTypes.size }
                .forEach {
                    XposedBridge.hookMethod(it, hook)
                    hooked = true
                }
            if(hooked) log("SUCCESS: Hooked ${clazz.simpleName}#$methodName (fuzzy match).")
        } catch (t: Throwable) {
            logError("Failed to hook ${clazz.simpleName}#$methodName: $t")
        }
    }

    // --- 1. 核心监控：进程状态 ---
    private fun hookAMSForProcessStateChanges(classLoader: ClassLoader) {
        try {
            val processRecordClass = findClass("com.android.server.am.ProcessRecord", classLoader) ?: return
            // setCurProcState 是更新进程adj/state后最终调用的核心方法，非常稳定
            // [**已修复**] 将 Int::class.javaPrimitiveType 强制转换为 Any
            findAndHookMethod(processRecordClass, "setCurProcState", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    handleProcessStateChange(param.thisObject)
                }
            }, Int::class.javaPrimitiveType as Any)
        } catch (t: Throwable) {
            logError("Failed to place process state hook: $t")
        }
    }

    private fun handleProcessStateChange(processRecord: Any) {
        val appInfo = XposedHelpers.getObjectField(processRecord, "info") as? ApplicationInfo ?: return
        val uid = appInfo.uid
        if (uid < Process.FIRST_APPLICATION_UID) return

        val adj = XposedHelpers.getIntField(processRecord, "mCurAdj")
        val procState = XposedHelpers.getIntField(processRecord, "mCurProcState")
        // PERCEPTIBLE_APP_ADJ = 200, PROCESS_STATE_TOP = 2
        val isForeground = adj <= 200 || procState == 2

        if (foregroundStatusCache.put(uid, isForeground) == isForeground) return

        val packageName = appInfo.packageName
        val userId = uid / 100000
        val eventType = if (isForeground) "event.app_foreground" else "event.app_background"
        log("EVENT: App ${packageName}(${userId}) -> ${if(isForeground) "foreground" else "background"}")
        sendEventToDaemon(eventType, mapOf("package_name" to packageName, "user_id" to userId))
    }

    // --- 2. 唤醒防御：Wakelock & Alarm ---
    private fun hookWakelocksAndAlarms(classLoader: ClassLoader) {
        findAndHookMethod(findClass("com.android.server.power.PowerManagerService", classLoader), "acquireWakeLockInternal", object : XC_MethodHook() {
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
            log("SUCCESS: Dynamic hook placed on AlarmManagerService#triggerAlarmsLocked.")
        }
    }

    // --- 3. 唤醒防御：Service & Broadcast ---
    private fun hookServicesAndBroadcasts(classLoader: ClassLoader) {
        findAndHookMethod(findClass("com.android.server.am.ActiveServices", classLoader), "bringUpServiceLocked", object: XC_MethodHook() {
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

    // --- 4. 唤醒防御：通知 ---
    private fun hookNotificationService(classLoader: ClassLoader) {
        findAndHookMethod(findClass("com.android.server.notification.NotificationManagerService", classLoader), "enqueueNotificationInternal", object: XC_MethodHook() {
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

    // --- 5. 冻结保护：信号 & ANR ---
    private fun hookProcessSignal(classLoader: ClassLoader) {
        val hook = object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val pid = param.args[0] as Int
                val signal = param.args[1] as Int
                if (signal == 9 && ConfigManager.isPidFrozen(pid)) { // 9 = SIGKILL
                    log("PROTECT: Intercepted SIGKILL for frozen PID: $pid. Thawing.")
                    requestTempUnfreezeForPid(pid)
                    param.result = null // 阻止发送SIGKILL
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(Process::class.java, "sendSignal", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, hook)
            XposedHelpers.findAndHookMethod(Process::class.java, "sendSignalQuiet", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, hook)
            log("SUCCESS: Hooked Process#sendSignal(Quiet).")
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

    // --- 6. 智能解冻：媒体 & 音频焦点 & FCM ---
    private fun hookMediaAndAudio(classLoader: ClassLoader) {
        // 音频焦点获取
        findAndHookMethod(findClass("com.android.server.audio.FocusRequester", classLoader), "handleFocusGain", object: XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val uid = XposedHelpers.getIntField(param.thisObject, "mCallingUid")
                if (ConfigManager.isUidFrozen(uid)) {
                    log("WAKEUP: App UID $uid gained audio focus. Requesting unfreeze.")
                    requestTempUnfreezeForUid(uid)
                }
            }
        })

        // FCM (通过修改广播Intent)
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

    // --- 辅助与通信 ---
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
                udsClient?.sendMessage(gson.toJson(message))
            } catch (e: Exception) {
                logError("Daemon send error for $type: $e")
            }
        }
    }

    private suspend fun setupPersistentUdsCommunication() {
        log("Persistent communication manager started.")
        while (coroutineContext.isActive) {
            try {
                udsClient?.start()
                delay(1000)
                val helloPayload = mapOf("pid" to Process.myPid(), "version" to TAG)
                udsClient?.sendMessage(gson.toJson(CerberusMessage(type = "event.probe_hello", payload = helloPayload)))
                udsClient?.incomingMessages?.collect { jsonLine -> ConfigManager.updateConfig(jsonLine) }
                logError("UDS message stream ended. Reconnecting...")
                udsClient?.stop()
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
                    if (frozenPids.isNotEmpty() || frozenUids.isNotEmpty()) {
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