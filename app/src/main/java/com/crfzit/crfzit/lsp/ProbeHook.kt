// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import android.os.Process
import com.crfzit.crfzit.data.model.AppInstanceKey
import com.crfzit.crfzit.data.model.CerberusMessage
import com.crfzit.crfzit.data.model.ProbeConfigUpdatePayload
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
import java.util.*
import java.util.concurrent.Executors

/**
 * Project Cerberus - System Probe (v8.1, Coroutine Fix)
 */
class ProbeHook : IXposedHookLoadPackage {

    // [编译修复] 修正 CoroutineScope 的创建方式以适配新版API
    private val probeScope = CoroutineScope(SupervisorJob() + Executors.newCachedThreadPool().asCoroutineDispatcher())

    private val udsClient = UdsClient(probeScope)
    private val gson = Gson()
    private val frozenAppsCache = MutableStateFlow<Set<AppInstanceKey>>(emptySet())
    private var lastReportedPackage: String? = null

    companion object {
        private const val TAG = "CerberusProbe_v8.1"
        private const val PER_USER_RANGE = 100000
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") {
            return
        }
        log("Loading into android package (system_server), PID: ${Process.myPid()}. Attaching hooks...")
        initializeHooks(lpparam.classLoader)
    }

    private fun initializeHooks(classLoader: ClassLoader) {
        setupUdsCommunication()
        hookActivityStateChange(classLoader)
        hookActivityManagerExtras(classLoader)
        hookPowerManager(classLoader)
        hookAlarmManager(classLoader)
        log("All hooks attached. Probe is active.")
    }

    private fun hookActivityStateChange(classLoader: ClassLoader) {
        try {
            val activityRecordClass = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", classLoader)

            XposedBridge.hookAllMethods(activityRecordClass, "setState", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val newState = param.args[0] as Enum<*>

                    if (newState.name != "RESUMED") {
                        return
                    }

                    val activityRecord = param.thisObject
                    val appInfo = XposedHelpers.getObjectField(activityRecord, "info") as? ApplicationInfo ?: return
                    val userId = XposedHelpers.getIntField(activityRecord, "mUserId")
                    val packageName = appInfo.packageName

                    if (packageName == lastReportedPackage) {
                        return
                    }
                    lastReportedPackage = packageName

                    log("Activity RESUMED: $packageName (user: $userId). Notifying daemon.")
                    sendToDaemon("event.top_app_changed", mapOf(
                        "package_name" to packageName,
                        "user_id" to userId
                    ))
                }
            })
            log("Hooked ActivityRecord#setState successfully. Now monitoring for RESUMED state.")
        } catch (t: Throwable) {
            logError("CRITICAL: Failed to hook ActivityRecord#setState: $t")
        }
    }


    private fun hookActivityManagerExtras(classLoader: ClassLoader) {
        try {
            val broadcastQueueClass = XposedHelpers.findClass("com.android.server.am.BroadcastQueue", classLoader)
            XposedBridge.hookAllMethods(broadcastQueueClass, "processNextBroadcast", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val queue = param.thisObject
                    val broadcastsField = XposedHelpers.getObjectField(queue, "mBroadcasts") as? ArrayList<*> ?: return
                    if (broadcastsField.isEmpty()) return
                    val br = broadcastsField[0] ?: return

                    val receivers = XposedHelpers.getObjectField(br, "receivers") as? List<*> ?: return
                    val firstReceiver = receivers.firstOrNull() ?: return
                    val targetApp = XposedHelpers.getObjectField(firstReceiver, "app")
                    val appInfo = targetApp?.let { XposedHelpers.getObjectField(it, "info") as? ApplicationInfo } ?: return

                    if (isAppFrozen(appInfo.packageName, appInfo.uid)) {
                        log("Broadcast Interception: Skipping for frozen app ${appInfo.packageName}")
                        val broadcastSuccessCode = XposedHelpers.getStaticIntField(ActivityManager::class.java, "BROADCAST_SUCCESS")
                        XposedHelpers.callMethod(queue, "finishReceiverLocked", br, broadcastSuccessCode, null, null, false, true)
                        XposedHelpers.callMethod(queue, "scheduleBroadcastsLocked")
                        param.result = null
                    }
                }
            })
            log("Hooked BroadcastQueue.")
        } catch(t: Throwable) {
            logError("Failed to hook BroadcastQueue: $t")
        }

        try {
            val appErrorsClass = XposedHelpers.findClass("com.android.server.am.AppErrors", classLoader)
            XposedBridge.hookAllMethods(appErrorsClass, "handleShowAnrUi", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val processRecord = param.args.firstOrNull { it?.javaClass?.name?.contains("ProcessRecord") == true } ?: return
                    val appInfo = XposedHelpers.getObjectField(processRecord, "info") as? ApplicationInfo ?: return
                    if (isAppFrozen(appInfo.packageName, appInfo.uid)) {
                        log("ANR Intervention: Suppressing for frozen app ${appInfo.packageName}")
                        param.result = null
                    }
                }
            })
            log("Hooked AppErrors.")
        } catch(t: Throwable) {
            logError("Failed to hook AppErrors: $t")
        }
    }

    private fun setupUdsCommunication() {
        udsClient.start()
        probeScope.launch(Dispatchers.IO) {
            delay(5000)
            sendToDaemon("event.probe_hello", mapOf("version" to "8.1.0", "pid" to Process.myPid()))

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
                    logError("Daemon msg processing error: $e")
                }
            }
        }
    }

    private fun isAppFrozen(packageName: String?, uid: Int): Boolean {
        if (packageName == null || uid < Process.FIRST_APPLICATION_UID) return false
        val userId = uid / PER_USER_RANGE
        val key = AppInstanceKey(packageName, userId)
        return frozenAppsCache.value.contains(key)
    }

    private fun hookPowerManager(classLoader: ClassLoader) {
        try {
            val pmsClass = XposedHelpers.findClass("com.android.server.power.PowerManagerService", classLoader)
            XposedBridge.hookAllMethods(pmsClass, "acquireWakeLock", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val callingUid = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.os.Binder", null), "getCallingUid") as Int
                    val pkg = param.args.firstOrNull { it is String && it.contains('.') } as? String
                    if (isAppFrozen(pkg, callingUid)) {
                        log("WakeLock Interception: Denying for frozen app $pkg")
                        param.result = null
                    }
                }
            })
            log("PowerManager hooks attached successfully.")
        } catch (t: Throwable) {
            logError("Failed to attach PowerManager hooks: $t")
        }
    }

    private fun hookAlarmManager(classLoader: ClassLoader) {
        try {
            val alarmManagerClassName = try {
                XposedHelpers.findClass("com.android.server.alarm.AlarmManagerService", classLoader)
                "com.android.server.alarm.AlarmManagerService"
            } catch (e: XposedHelpers.ClassNotFoundError) {
                "com.android.server.AlarmManagerService"
            }

            val amsClass = XposedHelpers.findClass(alarmManagerClassName, classLoader)
            XposedBridge.hookAllMethods(amsClass, "set", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pendingIntent = param.args.firstOrNull { it is android.app.PendingIntent } as? android.app.PendingIntent ?: return
                    val pkg = pendingIntent.creatorPackage
                    val uid = pendingIntent.creatorUid
                    if (isAppFrozen(pkg, uid)) {
                        log("Alarm Interception: Denying for frozen app $pkg")
                        param.result = null
                    }
                }
            })
            log("AlarmManager hooks attached successfully.")
        } catch (t: Throwable) {
            logError("Failed to attach AlarmManager hooks: $t")
        }
    }

    private fun sendToDaemon(type: String, payload: Any) {
        probeScope.launch {
            try {
                // [FIX] Updated protocol version to match daemon
                val message = CerberusMessage(8, type, null, payload)
                val jsonString = gson.toJson(message)
                udsClient.sendMessage(jsonString)
            } catch (e: Exception) {
                logError("Daemon send error: $e")
            }
        }
    }

    private fun log(message: String) = XposedBridge.log("$TAG: $message")
    private fun logError(message: String) = XposedBridge.log("$TAG: [ERROR] $message")
}