// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import com.crfzit.crfzit.data.model.*
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
// [FIX] 补全所有缺失的 import 语句
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
 * Project Cerberus - System Probe (v3.2, Robust Thaw)
 */
class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(Executors.newCachedThreadPool().asCoroutineDispatcher() + SupervisorJob())
    private val udsClient = UdsClient(probeScope)
    private val gson = Gson()

    private val frozenAppsCache = MutableStateFlow<Set<AppInstanceKey>>(emptySet())

    companion object {
        private const val TAG = "CerberusProbe_v3.2"
        private const val THAW_TIMEOUT_MS = 2000L // 同步等待解冻的超时时间
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" || lpparam.processName != "android") {
            return
        }
        log("Attaching to system_server (PID: ${Process.myPid()}). Probe v3.2 is activating...")
        setupUdsCommunication()
        hookActivityManagerService(lpparam.classLoader)
        hookAppErrors(lpparam.classLoader)
        hookPowerManagerService(lpparam.classLoader)
        log("Probe v3.2 attachment complete.")
    }

    private fun setupUdsCommunication() {
        udsClient.start()

        probeScope.launch(Dispatchers.IO) {
            delay(2000)
            sendToDaemon("event.probe_hello", mapOf("version" to "3.2.0", "pid" to Process.myPid()))

            udsClient.incomingMessages.collectLatest { jsonLine ->
                try {
                    val baseMsg = gson.fromJson(jsonLine, CerberusMessage::class.java)
                    if (baseMsg.type == "stream.probe_config_update") {
                        val type = object : TypeToken<CerberusMessage<ProbeConfigUpdatePayload>>() {}.type
                        val msg = gson.fromJson<CerberusMessage<ProbeConfigUpdatePayload>>(jsonLine, type)
                        val newCache = msg.payload.frozenApps.toSet()
                        if (frozenAppsCache.value != newCache) {
                            frozenAppsCache.value = newCache
                            log("Frozen app cache updated. Size: ${newCache.size}. Keys: ${newCache.joinToString { it.packageName }}")
                        }
                    }
                } catch (e: Exception) {
                    logError("Error processing message from daemon: $e. JSON: $jsonLine")
                }
            }
        }
    }

    private fun hookActivityManagerService(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)

            XposedHelpers.findAndHookMethod(
                amsClass, "startActivityAsUser",
                "android.app.IApplicationThread",
                String::class.java,
                Intent::class.java,
                String::class.java,
                "android.os.IBinder",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                "android.app.ProfilerInfo",
                "android.os.Bundle",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args[2] as? Intent ?: return
                        val userId = param.args[10] as? Int ?: return
                        val component = intent.component ?: return

                        val key = AppInstanceKey(component.packageName, userId)

                        if (frozenAppsCache.value.contains(key)) {
                            log("Gatekeeper: Intercepted start request for frozen app $key. Attempting synchronous thaw.")

                            val thawSuccess = runBlocking {
                                try {
                                    sendToDaemon("cmd.request_immediate_unfreeze", key)
                                    log("Thaw request sent for $key. Waiting for confirmation...")

                                    withTimeout(THAW_TIMEOUT_MS) {
                                        frozenAppsCache.first { updatedCache ->
                                            !updatedCache.contains(key)
                                        }
                                    }
                                    log("Thaw for $key confirmed by cache update.")
                                    true
                                } catch (e: TimeoutCancellationException) {
                                    logError("Thaw confirmation for $key timed out after ${THAW_TIMEOUT_MS}ms.")
                                    false
                                } catch (e: Exception) {
                                    logError("Error during thaw wait for $key: ${e.message}")
                                    false
                                }
                            }

                            if (thawSuccess) {
                                log("Thaw successful for $key. Allowing original method to proceed.")
                            } else {
                                logError("Thaw failed for $key. App will likely ANR. Allowing original method to proceed anyway.")
                            }
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "com.android.server.am.ProcessList", classLoader, "updateOomAdj",
                "com.android.server.am.ProcessRecord",
                Boolean::class.javaPrimitiveType,
                "com.android.server.am.OomAdjuster",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val processRecord = param.args[0]
                            val appInfo = XposedHelpers.getObjectField(processRecord, "info") as ApplicationInfo
                            val packageName = appInfo.packageName
                            val userId = XposedHelpers.getIntField(processRecord, "userId")
                            val oomAdj = XposedHelpers.getIntField(processRecord, "mState.mCurAdj")
                            val isForeground = oomAdj < 200

                            val payload = ProbeAppStateChangedPayload(
                                packageName = packageName,
                                userId = userId,
                                isForeground = isForeground,
                                oomAdj = oomAdj,
                                reason = "updateOomAdj"
                            )
                            sendToDaemon("event.app_state_changed", payload)
                        } catch (t: Throwable) {
                            logError("Error in updateOomAdj hook: $t")
                        }
                    }
                }
            )

            log("Successfully hooked AMS for state sensing and robust preemptive thaw.")
        } catch (t: Throwable) {
            logError("Failed to hook ActivityManagerService: $t")
        }
    }

    private fun hookAppErrors(classLoader: ClassLoader) {
        try {
            val appErrorsClass = XposedHelpers.findClass("com.android.server.am.AppErrors", classLoader)

            XposedHelpers.findAndHookMethod(appErrorsClass, "handleShowAnrUi", "com.android.server.am.ProcessRecord", String::class.java, object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val processRecord = param.args[0]
                    val anrReason = param.args[1] as? String ?: ""
                    val packageName = XposedHelpers.getObjectField(processRecord, "info")?.let {
                        (it as ApplicationInfo).packageName
                    } ?: return

                    if (anrReason.contains("Input dispatching timed out")) {
                        log("ANR Intervention: Suppressing potential ANR dialog for recently thawed app $packageName. Reason: $anrReason")
                        param.result = null
                    }
                }
            })
            log("Successfully hooked AppErrors for ANR intervention.")
        } catch (t: Throwable) {
            logError("Failed to hook AppErrors: $t")
        }
    }

    private fun hookPowerManagerService(classLoader: ClassLoader) {
        try {
            val pmsClass = XposedHelpers.findClass("com.android.server.power.PowerManagerService", classLoader)

            XposedHelpers.findAndHookMethod(pmsClass, "goToSleep", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, object : XC_MethodHook(){
                override fun afterHookedMethod(param: MethodHookParam?) {
                    log("Event: System is going to sleep (screen off).")
                    sendToDaemon("event.system_state_changed", ProbeSystemStateChangedPayload(screenOn = false))
                }
            })

            XposedHelpers.findAndHookMethod(pmsClass, "wakeUp", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java, String::class.java, object : XC_MethodHook(){
                override fun afterHookedMethod(param: MethodHookParam?) {
                    log("Event: System is waking up (screen on).")
                    sendToDaemon("event.system_state_changed", ProbeSystemStateChangedPayload(screenOn = true))
                }
            })

            log("Successfully hooked PMS methods for screen state.")
        } catch (t: Throwable) {
            logError("Failed to hook PowerManagerService: $t")
        }
    }

    private fun sendToDaemon(type: String, payload: Any) {
        probeScope.launch {
            try {
                val reqId = if (type.startsWith("cmd.")) UUID.randomUUID().toString() else null
                val message = CerberusMessage(3, type, reqId, payload)
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