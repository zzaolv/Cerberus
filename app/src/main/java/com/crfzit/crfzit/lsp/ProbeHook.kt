// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.AlarmManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.PowerManager
import android.os.Process
import com.crfzit.crfzit.data.model.*
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.util.*
import java.util.concurrent.Executors

/**
 * Project Cerberus - System Probe (v4.0, Active Interception Model)
 */
class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(Executors.newCachedThreadPool().asCoroutineDispatcher() + SupervisorJob())
    private val udsClient = UdsClient(probeScope)
    private val gson = Gson()

    // [FIX] Probe's local cache of frozen apps, synced from Daemon. This is the source of truth for all hooks.
    private val frozenAppsCache = MutableStateFlow<Set<AppInstanceKey>>(emptySet())
    
    companion object {
        private const val TAG = "CerberusProbe_v4.0"
        private const val THAW_TIMEOUT_MS = 3000L // Increased timeout for robust unfreezing
    }
    
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" || lpparam.processName != "android") {
            return
        }
        log("Attaching to system_server (PID: ${Process.myPid()}). Probe v4.0 (Active Interception) is activating...")
        setupUdsCommunication()
        
        // --- Full Interception Suite ---
        hookActivityManagerService(lpparam.classLoader) // For starting apps & ANR
        hookBroadcasts(lpparam.classLoader)
        hookWakelocks(lpparam.classLoader)
        hookAlarms(lpparam.classLoader)
        
        log("Probe v4.0 attachment complete.")
    }

    private fun setupUdsCommunication() {
        udsClient.start()
        probeScope.launch(Dispatchers.IO) {
            delay(5000)
            sendToDaemon("event.probe_hello", mapOf("version" to "4.0.0", "pid" to Process.myPid()))

            // Listen for config updates from the daemon
            udsClient.incomingMessages.collectLatest { jsonLine ->
                try {
                    val baseMsg = gson.fromJson(jsonLine, CerberusMessage::class.java)
                    if (baseMsg.type == "stream.probe_config_update") {
                        val type = object : TypeToken<CerberusMessage<ProbeConfigUpdatePayload>>() {}.type
                        val msg = gson.fromJson<CerberusMessage<ProbeConfigUpdatePayload>>(jsonLine, type)
                        val newCache = msg.payload.frozenApps.toSet()
                        if (frozenAppsCache.value != newCache) {
                            frozenAppsCache.value = newCache
                            log("Frozen app cache updated. Size: ${newCache.size}")
                        }
                    }
                } catch (e: Exception) {
                    logError("Error processing message from daemon: $e. JSON: $jsonLine")
                }
            }
        }
    }

    private fun isAppFrozen(packageName: String?, uid: Int): Boolean {
        if (packageName == null) return false
        val userId = uid / 100000
        val key = AppInstanceKey(packageName, userId)
        return frozenAppsCache.value.contains(key)
    }

    private fun hookActivityManagerService(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)

            // --- Robust Unfreeze Hook ---
            XposedHelpers.findAndHookMethod(
                amsClass, "startActivityAsUser",
                "android.app.IApplicationThread", String::class.java, Intent::class.java, String::class.java,
                "android.os.IBinder", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                "android.app.ProfilerInfo", "android.os.Bundle", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args[2] as? Intent ?: return
                        val callerUid = XposedHelpers.getIntField(param.thisObject, "mCallingUid")
                        val component = intent.component ?: return
                        
                        if (isAppFrozen(component.packageName, callerUid)) {
                            val key = AppInstanceKey(component.packageName, callerUid / 100000)
                            log("Gatekeeper: Intercepted start request for frozen app $key. Attempting synchronous thaw.")
                            
                            val thawSuccess = runBlocking {
                                try {
                                    sendToDaemon("cmd.request_immediate_unfreeze", key)
                                    withTimeout(THAW_TIMEOUT_MS) {
                                        frozenAppsCache.first { !it.contains(key) }
                                    }
                                    log("Thaw for $key confirmed by cache update.")
                                    true
                                } catch (e: Exception) {
                                    logError("Thaw failed for $key: ${e.message}")
                                    false
                                }
                            }
                            
                            if (!thawSuccess) {
                                logError("Thaw FAILED for $key. Aborting start.")
                                param.result = ActivityManager.START_ABORTED
                            }
                        }
                    }
                }
            )

            // --- ANR Intervention Hook ---
            val appErrorsClass = XposedHelpers.findClass("com.android.server.am.AppErrors", classLoader)
            XposedHelpers.findAndHookMethod(appErrorsClass, "handleShowAnrUi", "com.android.server.am.ProcessRecord", String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val processRecord = param.args[0]
                    val appInfo = XposedHelpers.getObjectField(processRecord, "info") as ApplicationInfo
                    if (isAppFrozen(appInfo.packageName, appInfo.uid)) {
                        log("ANR Intervention: Suppressing ANR dialog for frozen app ${appInfo.packageName}")
                        param.result = null
                    }
                }
            })
            log("Hooked AMS for start/ANR.")
        } catch (t: Throwable) {
            logError("Failed to hook AMS: $t")
        }
    }

    private fun hookBroadcasts(classLoader: ClassLoader) {
        try {
            val broadcastQueueClass = XposedHelpers.findClass("com.android.server.am.BroadcastQueue", classLoader)
            XposedHelpers.findAndHookMethod(broadcastQueueClass, "processNextBroadcast", Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val mBroadcasts = XposedHelpers.getObjectField(param.thisObject, "mBroadcasts") as ArrayList<*>
                    if (mBroadcasts.isEmpty()) return
                    val broadcastRecord = mBroadcasts[0]
                    val receivers = XposedHelpers.getObjectField(broadcastRecord, "receivers") as? List<*> ?: return
                    
                    receivers.forEach { receiver ->
                        if (receiver is BroadcastReceiver.PendingResult) {
                            val targetPackage = receiver.targetPackage
                            val app = XposedHelpers.callMethod(XposedHelpers.getObjectField(param.thisObject, "mService"), "getRecordForAppLocked", receiver) as? Application
                            val uid = app?.applicationInfo?.uid ?: -1
                            if (uid != -1 && isAppFrozen(targetPackage, uid)) {
                                log("Broadcast Interception: Skipping broadcast for frozen app $targetPackage")
                                XposedHelpers.callMethod(receiver, "finish")
                                param.result = null
                            }
                        }
                    }
                }
            })
            log("Hooked BroadcastQueue.")
        } catch (t: Throwable) {
            logError("Failed to hook Broadcasts: $t")
        }
    }

    private fun hookWakelocks(classLoader: ClassLoader) {
        try {
            val pmsClass = XposedHelpers.findClass("com.android.server.power.PowerManagerService", classLoader)
            XposedHelpers.findAndHookMethod(pmsClass, "acquireWakeLock", "android.os.IBinder", Int::class.javaPrimitiveType, String::class.java, String::class.java, "android.os.WorkSource", "android.history.HistoryTag", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callingUid = XposedHelpers.getIntField(param.thisObject, "mCallingUid")
                        val pkg = param.args[3] as String
                        if (isAppFrozen(pkg, callingUid)) {
                            log("WakeLock Interception: Denying acquire for frozen app $pkg")
                            param.result = null
                        }
                    }
                })
            log("Hooked PowerManagerService for WakeLocks.")
        } catch (t: Throwable) {
            logError("Failed to hook WakeLocks: $t")
        }
    }

    private fun hookAlarms(classLoader: ClassLoader) {
        try {
            val alarmManagerClass = XposedHelpers.findClass("com.android.server.AlarmManagerService", classLoader)
            XposedHelpers.findAndHookMethod(alarmManagerClass, "set", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, "android.app.PendingIntent", "android.app.IAlarmListener", String::class.java, "android.os.WorkSource", "android.app.AlarmManager.AlarmClockInfo",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callingUid = XposedHelpers.getIntField(param.thisObject, "mCallingUid")
                        val pkg = param.args[6] as String
                        if (isAppFrozen(pkg, callingUid)) {
                            log("Alarm Interception: Denying set for frozen app $pkg")
                            param.result = null
                        }
                    }
                })
            log("Hooked AlarmManagerService.")
        } catch (t: Throwable) {
            logError("Failed to hook Alarms: $t")
        }
    }

    private fun sendToDaemon(type: String, payload: Any) {
        probeScope.launch {
            try {
                val reqId = if (type.startsWith("cmd.")) UUID.randomUUID().toString() else null
                val message = CerberusMessage(4, type, reqId, payload)
                val jsonString = gson.toJson(message)
                udsClient.sendMessage(jsonString)
            } catch (e: Exception) {
                logError("Error sending event '$type' to daemon: ${e.message}")
            }
        }
    }

    private fun log(message: String) = XposedBridge.log("$TAG: $message")
    private fun logError(message: String) = XposedBridge.log("$TAG: [ERROR] $message")
}