// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.PowerManager
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
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.collections.ArrayList
import kotlin.coroutines.coroutineContext

@OptIn(DelicateCoroutinesApi::class)
class ProbeHook : IXposedHookLoadPackage {

    private val scope = GlobalScope
    private var udsClient: UdsClient? = null
    private val gson = Gson()

    @Volatile
    private var powerManager: PowerManager? = null
    private val foregroundStatusCache = ConcurrentHashMap<Int, Boolean>()

    companion object {
        private const val TAG = "CerberusProbe_v16_Final" // 版本号更新
        private const val PER_USER_RANGE = 100000
        const val FLAG_INCLUDE_STOPPED_PACKAGES = 32 // Intent.FLAG_INCLUDE_STOPPED_PACKAGES
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") {
            return
        }

        log("Loading into system_server (PID: ${Process.myPid()}).")
        val singleThreadContext = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        // [修复] 创建一个有 Job 的 CoroutineScope，而不是使用 GlobalScope
        val probeScope = CoroutineScope(SupervisorJob() + singleThreadContext)

        udsClient = UdsClient(probeScope)
        probeScope.launch {
            setupPersistentUdsCommunication()
        }

        try {
            val classLoader = lpparam.classLoader
            hookSystemServerForCoreObjects(classLoader)
            hookAMSForProcessStateChanges(classLoader)
            hookWakelocksAndAlarms(classLoader)
            hookMediaSessionService(classLoader)
            hookBroadcastAndRestrictions(classLoader)
        } catch (t: Throwable) {
            logError("CRITICAL: Failed during hook placement: $t")
        }
    }

    // --- 功能1: 进程状态监控 ---
    private fun hookSystemServerForCoreObjects(classLoader: ClassLoader) {
        try {
            val systemServerClass = XposedHelpers.findClass("com.android.server.SystemServer", classLoader)
            XposedBridge.hookAllMethods(systemServerClass, "startOtherServices", object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (powerManager == null) {
                        try {
                            val pm = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.os.ServiceManager", classLoader), "getService", "power")
                            if (pm != null) {
                                val pmsClass = XposedHelpers.findClass("com.android.server.power.PowerManagerService", classLoader)
                                powerManager = XposedHelpers.findFirstFieldByExactType(pmsClass, PowerManager::class.java).get(null) as? PowerManager
                                log("Successfully got PowerManager instance via ServiceManager.")
                            }
                        } catch (t: Throwable) {
                            logError("Failed to get PowerManager via ServiceManager fallback: $t")
                        }
                    }
                }
            })
        } catch (t: Throwable) {
            logError("Failed to hook SystemServer for core objects: $t")
        }
    }

    private fun hookAMSForProcessStateChanges(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
            val processRecordClass = XposedHelpers.findClass("com.android.server.am.ProcessRecord", classLoader)
            var hookedMethodCount = 0
            amsClass.declaredMethods.filter { it.parameterTypes.firstOrNull() == processRecordClass }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        handleProcessStateChange(param.args[0] as Any)
                    }
                })
                hookedMethodCount++
            }
            if (hookedMethodCount > 0) log("SUCCESS: Universal hook placed on $hookedMethodCount AMS methods.")
            else logError("Universal hook on AMS failed, no suitable methods found.")
        } catch (t: Throwable) {
            logError("Failed to place universal hook on AMS: $t")
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
        log("EVENT: App became ${if(isForeground) "foreground" else "background"}: $packageName (user: $userId)")
        sendEventToDaemon(eventType, mapOf("package_name" to packageName, "user_id" to userId))
    }

    // --- 功能2: 唤醒锁/警报防御 ---
    private fun hookWakelocksAndAlarms(classLoader: ClassLoader) {
        try {
            val pmsClass = XposedHelpers.findClass("com.android.server.power.PowerManagerService", classLoader)
            pmsClass.declaredMethods.filter { it.name == "acquireWakeLockInternal" }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val uidIndex = param.args.indexOfFirst { it is Int && it >= Process.FIRST_APPLICATION_UID }
                        if (uidIndex != -1 && ConfigManager.isUidFrozen(param.args[uidIndex] as Int)) {
                            param.result = null
                        }
                    }
                })
            }
            log("Dynamic hook placed on PowerManagerService#acquireWakeLockInternal.")
        } catch (t: Throwable) {
            logError("Failed to hook PowerManagerService: $t")
        }

        try {
            val amsClass = XposedHelpers.findClass("com.android.server.alarm.AlarmManagerService", classLoader)
            XposedBridge.hookAllMethods(amsClass, "triggerAlarmsLocked", object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    (param.args.firstOrNull { it is ArrayList<*> } as? ArrayList<*>)?.removeIf { alarm ->
                        val uid = XposedHelpers.getIntField(alarm!!, "uid")
                        ConfigManager.isUidFrozen(uid)
                    }
                }
            })
            log("Dynamic hook placed on AlarmManagerService#triggerAlarmsLocked.")
        } catch (t: Throwable) {
            logError("Failed to hook AlarmManagerService: $t")
        }
    }

    // --- 功能3: 媒体控制解冻 ---
    private fun hookMediaSessionService(classLoader: ClassLoader) {
        try {
            val mssClass = XposedHelpers.findClass("com.android.server.media.MediaSessionService", classLoader)
            val dispatchMethod = mssClass.declaredMethods.find {
                it.name == "dispatchMediaKeyEvent" && it.parameterTypes.size >= 3
            }

            if (dispatchMethod != null) {
                XposedBridge.hookMethod(dispatchMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val packageName = param.args.find { it is String } as? String ?: return
                            val callingUidArg = param.args.find { arg ->
                                arg?.javaClass?.name?.contains("MediaSessionRecord") == true || arg?.javaClass?.name?.contains("SessionPlayer") == true
                            }
                            val callingUid = if (callingUidArg != null) XposedHelpers.getIntField(callingUidArg, "mUid") else -1

                            if (callingUid >= Process.FIRST_APPLICATION_UID) {
                                val userId = callingUid / PER_USER_RANGE
                                log("WAKEUP: Media key event for $packageName (uid: $callingUid). Notifying daemon.")
                                sendEventToDaemon("event.app_wakeup_request", mapOf("package_name" to packageName, "user_id" to userId))
                            }
                        } catch(t: Throwable) {
                            logError("Error in dispatchMediaKeyEvent hook: $t")
                        }
                    }
                })
                log("SUCCESS: Hook placed on MediaSessionService#${dispatchMethod.name} for media wakeup.")
            } else {
                logError("Hook on MediaSessionService failed, dispatchMediaKeyEvent method not found.")
            }
        } catch (t: Throwable) {
            logError("Failed to hook MediaSessionService: $t")
        }
    }

    // --- 功能4: FCM推送解冻 ---
    private val fcmBypassHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val intent = param.args.find { it is Intent } as? Intent ?: return
            if (isGcmOrFcmIntent(intent)) {
                val targetPackage = intent.`package` ?: intent.component?.packageName
                if (targetPackage != null) {
                    log("FCM: Bypassing restriction '${param.method.declaringClass.simpleName}#${param.method.name}' for $targetPackage")
                }
                val method = param.method as? Method
                if (method?.returnType == Boolean::class.javaPrimitiveType) {
                    param.result = false
                } else {
                    param.result = null
                }
            }
        }
    }

    private fun hookBroadcastAndRestrictions(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
            val broadcastIntentMethod = amsClass.declaredMethods.find {
                it.name == "broadcastIntent" && it.parameterCount > 5 && it.parameterTypes.contains(Intent::class.java)
            }
            XposedBridge.hookMethod(broadcastIntentMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args.find { it is Intent } as? Intent ?: return
                    if (isGcmOrFcmIntent(intent)) {
                        val originalFlags = intent.flags
                        intent.flags = originalFlags or FLAG_INCLUDE_STOPPED_PACKAGES
                        log("FCM: Added FLAG_INCLUDE_STOPPED_PACKAGES to intent for ${intent.`package`}")

                        val targetPackage = intent.`package` ?: intent.component?.packageName
                        if (targetPackage != null) {
                            requestTempUnfreezeForPackage(targetPackage)
                        }
                    }
                }
            })
            log("SUCCESS: Hooked AMS#broadcastIntent for FCM wakeup.")
        } catch(t: Throwable) {
            logError("Failed to hook AMS#broadcastIntent: $t")
        }

        log("Scanning for ROM-specific background restrictions...")
        var hookedCount = 0
        val restrictionHooks = mapOf(
            "com.miui.server.smartpower.SmartPowerPolicyManager" to "shouldInterceptService",
            "com.android.server.am.BroadcastQueueModernStubImpl" to "checkApplicationAutoStart",
            "com.android.server.am.BroadcastQueueImpl" to "checkApplicationAutoStart",
            "com.android.server.am.BroadcastQueueInjector" to "checkApplicationAutoStart",
            "com.android.server.am.OplusAppStartupManager" to "shouldPreventSendReceiverReal",
            "com.vivo.services.rms.proxy.ProxyRms" to "shouldIntercept"
        )

        restrictionHooks.forEach { (className, methodName) ->
            try {
                val clazz = XposedHelpers.findClassIfExists(className, classLoader)
                clazz?.declaredMethods?.filter { it.name == methodName }?.forEach { method ->
                    XposedBridge.hookMethod(method, fcmBypassHook)
                    log("SUCCESS: Hooked ${clazz.simpleName}#${method.name}.")
                    hookedCount++
                }
            } catch (t: Throwable) {
                // Ignore
            }
        }

        if (hookedCount == 0) log("No known ROM restrictions were found. FCM unfreeze will rely solely on broadcastIntent hook.")
        else log("Total $hookedCount ROM restriction points have been hooked for FCM.")
    }

    // --- 辅助与通信 ---
    private fun isGcmOrFcmIntent(intent: Intent): Boolean {
        val action = intent.action ?: return false
        return action == "com.google.android.c2dm.intent.RECEIVE" || action == "com.google.firebase.MESSAGING_EVENT"
    }

    private fun requestTempUnfreezeForPackage(packageName: String) {
        scope.launch {
            try {
                log("FCM: Requesting temp unfreeze for package: $packageName")
                val payload = mapOf("package_name" to packageName)
                val message = CerberusMessage(type = "cmd.request_temp_unfreeze_pkg", payload = payload)
                udsClient?.sendMessage(gson.toJson(message))
            } catch (e: Exception) { logError("Daemon send error for temp unfreeze: $e") }
        }
    }

    private fun sendEventToDaemon(type: String, payload: Any) {
        scope.launch {
            try {
                val message = CerberusMessage(type = type, payload = payload)
                udsClient?.sendMessage(gson.toJson(message))
            } catch (e: Exception) { logError("Daemon send error: $e") }
        }
    }

    private suspend fun setupPersistentUdsCommunication() {
        log("Persistent communication manager started.")
        // [核心修复] 使用 coroutineContext.isActive 来安全地检查协程状态
        while (coroutineContext.isActive) {
            try {
                udsClient?.start()
                delay(1000)
                val helloPayload = mapOf("pid" to Process.myPid(), "version" to TAG)
                udsClient?.sendMessage(gson.toJson(CerberusMessage(type = "event.probe_hello", payload = helloPayload)))
                udsClient?.incomingMessages?.collect { jsonLine ->
                    try { ConfigManager.updateConfig(jsonLine) }
                    catch (e: Exception) { logError("Error processing config message: $e") }
                }
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
        private val jsonParser = JsonParser()
        fun updateConfig(jsonString: String) {
            try {
                val payload = jsonParser.parse(jsonString).asJsonObject.getAsJsonObject("payload")
                if (payload.has("frozen_uids")) {
                    val uids = payload.getAsJsonArray("frozen_uids").map { it.asInt }.toSet()
                    if (uids != frozenUids) {
                        frozenUids = uids
                        XposedBridge.log("[$TAG]: Config updated. Now tracking ${uids.size} frozen uids.")
                    }
                }
            } catch (e: Exception) { XposedBridge.log("[$TAG]: [ERROR] Failed to parse probe config: $e") }
        }
        fun isUidFrozen(uid: Int): Boolean = frozenUids.contains(uid)
    }

    private fun log(message: String) = XposedBridge.log("[$TAG] $message")
    private fun logError(message: String) = XposedBridge.log("[$TAG] [ERROR] $message")
}