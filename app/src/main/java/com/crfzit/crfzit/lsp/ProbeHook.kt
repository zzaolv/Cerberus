// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import android.os.Process
import com.crfzit.crfzit.data.model.AppInstanceKey
import com.crfzit.crfzit.data.model.CerberusMessage
import com.crfzit.crfzit.data.model.ProbeAppStateChangedPayload
import com.crfzit.crfzit.data.model.ProbeConfigUpdatePayload
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(SupervisorJob() + Executors.newCachedThreadPool().asCoroutineDispatcher())
    private val udsClient = UdsClient(probeScope)
    private val gson = Gson()
    private val frozenAppsCache = MutableStateFlow<Set<AppInstanceKey>>(emptySet())
    private val pendingThawRequests = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

    companion object {
        private const val TAG = "CerberusProbe_v11.0_Refactored"
        private const val PER_USER_RANGE = 100000
        private const val THAW_TIMEOUT_MS = 1500L

        // Android framework internal constants (might need adjustment for some ROMs)
        private const val PROCESS_STATE_TOP = 2
        private const val PROCESS_STATE_CACHED_ACTIVITY = 15
        private const val PROCESS_STATE_CACHED_EMPTY = 18
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        log("Loading into android package (system_server), PID: ${Process.myPid()}. Attaching hooks...")
        initializeHooks(lpparam.classLoader)
    }

    private fun initializeHooks(classLoader: ClassLoader) {
        setupUdsCommunication()
        hookActivityStarter(classLoader) // For cold start unfreeze
        hookActivityManagerService(classLoader) // For precise state tracking
        log("All essential hooks attached. Probe is active.")
    }

    // [CRITICAL REFACTOR] This hook is now the primary source of state intelligence.
    private fun hookActivityManagerService(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
            // This method is called whenever a process's scheduling group or state changes.
            // It's the most reliable place to know when an app becomes TOP or CACHED.
            XposedHelpers.findAndHookMethod(
                amsClass,
                "updateOomAdj",
                "com.android.server.am.ProcessRecord",
                Boolean::class.javaPrimitiveType,
                "com.android.server.am.OomAdjuster",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val processRecord = param.args[0] ?: return
                        val appInfo = XposedHelpers.getObjectField(processRecord, "info") as? ApplicationInfo ?: return
                        val packageName = appInfo.packageName
                        val uid = appInfo.uid

                        if (uid < Process.FIRST_APPLICATION_UID) return

                        val userId = uid / PER_USER_RANGE
                        val procState = XposedHelpers.getIntField(processRecord, "mState.mCurProcState")

                        when (procState) {
                            PROCESS_STATE_TOP -> {
                                // This app just became the foreground app.
                                log("StateChange: $packageName (user $userId) is now FOREGROUND (TOP).")
                                sendAppStateChange(packageName, userId, isForeground = true, isCached = false, reason = " Became TOP")
                            }
                            in PROCESS_STATE_CACHED_ACTIVITY..PROCESS_STATE_CACHED_EMPTY -> {
                                // This app has just been demoted to a cached state. It's now a candidate for freezing.
                                log("StateChange: $packageName (user $userId) is now CACHED.")
                                sendAppStateChange(packageName, userId, isForeground = false, isCached = true, reason = "Became CACHED")
                            }
                        }
                    }
                }
            )
            log("Hooked ActivityManagerService#updateOomAdj for precise state tracking.")
        } catch (t: Throwable) {
            logError("CRITICAL: Failed to hook ActivityManagerService: $t")
        }
    }

    // [Unchanged] This hook remains essential for handling cold starts of frozen apps.
    private fun hookActivityStarter(classLoader: ClassLoader) {
        try {
            val activityStarterClass = XposedHelpers.findClass("com.android.server.wm.ActivityStarter", classLoader)
            XposedBridge.hookAllMethods(activityStarterClass, "execute", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val request = XposedHelpers.getObjectField(param.thisObject, "mRequest") ?: return
                    val intent = XposedHelpers.getObjectField(request, "intent") as? android.content.Intent ?: return
                    val callingUid = XposedHelpers.getIntField(request, "callingUid")
                    if (callingUid < Process.FIRST_APPLICATION_UID) return
                    val component = intent.component ?: return
                    val packageName = component.packageName
                    val userId = callingUid / PER_USER_RANGE

                    if (isAppFrozen(packageName, userId)) {
                        log("Intercepted cold launch of frozen app: $packageName (user: $userId). Requesting pre-emptive thaw...")
                        val success = requestThawAndWait(packageName, userId)
                        if (!success) {
                            logError("Thaw FAILED for $packageName. Aborting launch.")
                            param.result = ActivityManager.START_ABORTED
                        } else {
                            log("Thaw successful for $packageName. Proceeding with launch.")
                        }
                    }
                }
            })
            log("Hooked ActivityStarter#execute for pre-emptive thaw.")
        } catch (t: Throwable) {
            logError("CRITICAL: Failed to hook ActivityStarter: $t")
        }
    }

    private fun requestThawAndWait(packageName: String, userId: Int): Boolean {
        val reqId = UUID.randomUUID().toString()
        val future = CompletableFuture<Boolean>()
        try {
            pendingThawRequests[reqId] = future
            val payload = AppInstanceKey(packageName, userId)
            sendToDaemon("cmd.request_immediate_unfreeze", payload, reqId)
            return future.get(THAW_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            logError("Exception in requestThawAndWait for $packageName: ${e.message}")
            return false
        } finally {
            pendingThawRequests.remove(reqId)
        }
    }

    private fun setupUdsCommunication() {
        udsClient.start()
        probeScope.launch(Dispatchers.IO) {
            delay(5000)
            sendToDaemon("event.probe_hello", mapOf("version" to "11.0", "pid" to Process.myPid()))
            udsClient.incomingMessages.collectLatest { jsonLine ->
                try {
                    val baseMsg = gson.fromJson(jsonLine, BaseMessage::class.java)
                    if (baseMsg.type.startsWith("resp.")) {
                        pendingThawRequests.remove(baseMsg.requestId)?.complete(baseMsg.type == "resp.unfreeze_complete")
                    } else if (baseMsg.type == "stream.probe_config_update") {
                        val type = object : TypeToken<CerberusMessage<ProbeConfigUpdatePayload>>() {}.type
                        val msg = gson.fromJson<CerberusMessage<ProbeConfigUpdatePayload>>(jsonLine, type)
                        val newCache = msg.payload.frozenApps.toSet()
                        if (frozenAppsCache.value != newCache) {
                            frozenAppsCache.value = newCache
                            log("Updated frozen apps cache. Size: ${newCache.size}")
                        }
                    }
                } catch (e: Exception) { logError("Error processing message from daemon: $e") }
            }
        }
    }

    private fun isAppFrozen(packageName: String?, userId: Int): Boolean {
        if (packageName == null) return false
        val key = AppInstanceKey(packageName, userId)
        return frozenAppsCache.value.contains(key)
    }

    // [NEW] Helper function to send the detailed app state change event.
    private fun sendAppStateChange(packageName: String, userId: Int, isForeground: Boolean, isCached: Boolean, reason: String) {
        val payload = ProbeAppStateChangedPayload(
            packageName = packageName,
            userId = userId,
            isForeground = isForeground,
            isCached = isCached,
            reason = reason
        )
        sendToDaemon("event.app_state_changed", payload)
    }

    private fun sendToDaemon(type: String, payload: Any, reqId: String? = null) {
        probeScope.launch {
            try {
                udsClient.sendMessage(gson.toJson(CerberusMessage(11, type, reqId, payload)))
            } catch (e: Exception) {
                logError("Daemon send error: $e")
            }
        }
    }

    private data class BaseMessage(val type: String, @SerializedName("req_id") val requestId: String?)
    private fun log(message: String) = XposedBridge.log("$TAG: $message")
    private fun logError(message: String) = XposedBridge.log("$TAG: [ERROR] $message")
}