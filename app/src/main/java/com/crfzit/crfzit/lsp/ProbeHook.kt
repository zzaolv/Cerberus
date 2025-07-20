// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

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
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.Executors

class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private var udsClient: UdsClient? = null
    private val gson = Gson()
    private var powerManager: PowerManager? = null

    companion object {
        private const val TAG = "CerberusProbe_v12.4_compat"
        private const val DECISION_FIELD_NAME = "cerberus_decision"
        private const val PER_USER_RANGE = 100000
    }

    private data class Decision(val shouldBeFrozen: Boolean)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") {
            log("Loading into system_server (PID: ${Process.myPid()}). Initializing...")
            udsClient = UdsClient(probeScope)
            initializeHooks(lpparam.classLoader)
        }
    }

    private fun initializeHooks(classLoader: ClassLoader) {
        setupUdsCommunication()
        try {
            hookOomAdjuster(classLoader)
            hookServices(classLoader)
        } catch (t: Throwable) {
            logError("CRITICAL: Failed to attach hooks in system_server: $t")
        }
    }

    // [CRITICAL COMPATIBILITY FIX] Use hookAllMethods to find the correct method signature
    private fun hookOomAdjuster(classLoader: ClassLoader) {
        val oomAdjusterClass = XposedHelpers.findClass("com.android.server.am.OomAdjuster", classLoader)
        val processRecordClass = XposedHelpers.findClass("com.android.server.am.ProcessRecord", classLoader)

        XposedBridge.hookAllMethods(oomAdjusterClass, "computeOomAdj", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // Find the correct ProcessRecord argument, which is usually the first one.
                val process = param.args.firstOrNull { it != null && it::class.java == processRecordClass } ?: return

                try {
                    val procState = XposedHelpers.getIntField(process, "mState.mCurProcState")
                    val decision = makeFreezeDecision(process, procState)
                    XposedHelpers.setAdditionalInstanceField(process, DECISION_FIELD_NAME, decision)
                    param.result = null // Prevent original method
                } catch(e: Throwable) {
                    logError("Error in computeOomAdj hook: ${e.message}")
                    // Let the original method run if we fail
                }
            }
        })

        XposedBridge.hookAllMethods(oomAdjusterClass, "applyOomAdj", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val process = param.args.firstOrNull { it != null && it::class.java == processRecordClass } ?: return

                try {
                    val decision = XposedHelpers.getAdditionalInstanceField(process, DECISION_FIELD_NAME) as? Decision ?: return

                    val isCurrentlyFrozen = try {
                        XposedHelpers.getBooleanField(process, "mState.isFrozen()")
                    } catch (e: NoSuchFieldError) { false }

                    if (decision.shouldBeFrozen && !isCurrentlyFrozen) {
                        executeFreeze(process)
                    } else if (!decision.shouldBeFrozen && isCurrentlyFrozen) {
                        executeUnfreeze(process)
                    }
                    param.result = null // Prevent original method
                } catch(e: Throwable) {
                    logError("Error in applyOomAdj hook: ${e.message}")
                }
            }
        })

        log("Hooked all OomAdjuster methods successfully.")
    }
    private fun hookServices(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
            XposedBridge.hookAllConstructors(amsClass, object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ams = param.thisObject
                    powerManager = XposedHelpers.getObjectField(ams, "mPowerManager") as? PowerManager
                    if (powerManager != null) {
                        log("Got PowerManager instance.")
                    } else {
                        logError("Failed to get PowerManager instance.")
                    }
                }
            })
        } catch (t: Throwable) {
            logError("Error hooking services: $t")
        }
    }

    private fun makeFreezeDecision(process: Any, procState: Int): Decision {
        val appInfo = XposedHelpers.getObjectField(process, "info") as? android.content.pm.ApplicationInfo ?: return Decision(false)
        val uid = appInfo.uid
        val packageName = appInfo.packageName

        if (uid < Process.FIRST_APPLICATION_UID || packageName == "android") {
            return Decision(false)
        }

        if (!ConfigManager.isEnabled()) return Decision(false)
        if (ConfigManager.isWhitelisted(packageName)) return Decision(false)
        if (ConfigManager.freezeOnScreenOff() && powerManager?.isInteractive == true) return Decision(false)

        val isInteresting = XposedHelpers.callMethod(process, "isInterestingToUser") as Boolean
        if (isInteresting) return Decision(false)

        if (ConfigManager.exemptForegroundServices() && XposedHelpers.callMethod(process, "hasForegroundServices") as Boolean) {
            return Decision(false)
        }

        // Corresponds to PROCESS_STATE_CACHED_ACTIVITY and above
        val isCached = procState >= 15
        return Decision(isCached)
    }

    private fun executeFreeze(process: Any) {
        val appInfo = XposedHelpers.getObjectField(process, "info") as android.content.pm.ApplicationInfo
        val payload = ProbeFreezePayload(
            packageName = appInfo.packageName,
            userId = appInfo.uid / PER_USER_RANGE,
            pid = XposedHelpers.getIntField(process, "mPid"),
            uid = appInfo.uid
        )
        sendToDaemon("cmd.freeze_process", payload)
        log("Requested FREEZE for ${appInfo.packageName}")
    }

    private fun executeUnfreeze(process: Any) {
        val appInfo = XposedHelpers.getObjectField(process, "info") as android.content.pm.ApplicationInfo
        val payload = ProbeUnfreezePayload(
            packageName = appInfo.packageName,
            userId = appInfo.uid / PER_USER_RANGE,
            pid = XposedHelpers.getIntField(process, "mPid"),
            uid = appInfo.uid
        )
        sendToDaemon("cmd.unfreeze_process", payload)
        log("Requested UNFREEZE for ${appInfo.packageName}")
    }

    private fun setupUdsCommunication() {
        udsClient?.start()
        probeScope.launch(Dispatchers.IO) {
            delay(5000)
            sendToDaemon("event.probe_hello", mapOf("pid" to Process.myPid()))
            udsClient?.incomingMessages?.collectLatest { jsonLine ->
                try {
                    val msg = gson.fromJson(jsonLine, CerberusMessage::class.java)
                    if (msg.type == "stream.probe_config_update") {
                        ConfigManager.updateConfig(jsonLine)
                    }
                } catch (e: Exception) { logError("Error processing message from daemon: $e") }
            }
        }
    }

    private fun sendToDaemon(type: String, payload: Any) {
        udsClient?.let { client ->
            probeScope.launch {
                try {
                    client.sendMessage(gson.toJson(CerberusMessage(type = type, payload = payload)))
                } catch (e: Exception) { logError("Daemon send error: $e") }
            }
        }
    }

    private object ConfigManager {
        @Volatile private var config: FullConfigPayload? = null
        private val gson = Gson()

        fun updateConfig(json: String) {
            try {
                val type = object : TypeToken<CerberusMessage<FullConfigPayload>>() {}.type
                val msg = gson.fromJson<CerberusMessage<FullConfigPayload>>(json, type)
                config = msg.payload
                XposedBridge.log("$TAG: Probe config updated successfully.")
            } catch (e: Exception) {
                XposedBridge.log("$TAG: [ERROR] Failed to parse probe config: $e")
            }
        }

        fun isEnabled(): Boolean = config?.masterConfig?.isEnabled ?: false
        fun freezeOnScreenOff(): Boolean = config?.masterConfig?.freezeOnScreenOff ?: true
        fun isWhitelisted(pkg: String): Boolean = config?.policies?.any { it.packageName == pkg && it.policy == 0 } ?: true
        fun exemptForegroundServices(): Boolean = config?.exemptConfig?.exemptForegroundServices ?: true
    }

    private fun log(message: String) = XposedBridge.log("$TAG: $message")
    private fun logError(message: String) = XposedBridge.log("$TAG: [ERROR] $message")
}