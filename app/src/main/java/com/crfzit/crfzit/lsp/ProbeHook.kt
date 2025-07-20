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
    private lateinit var powerManager: PowerManager

    companion object {
        private const val TAG = "CerberusProbe_v12.0_final"
        private const val PER_USER_RANGE = 100000
        private const val DECISION_FIELD_NAME = "cerberus_decision"
    }
    
    // Simple data class to hold the decision made in computeOomAdj
    private data class Decision(val shouldBeFrozen: Boolean)

    // --- Xposed Entry Point ---
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
            log("All hooks in system_server attached successfully.")
        } catch (t: Throwable) {
            logError("CRITICAL: Failed to attach hooks in system_server: $t")
        }
    }
    
    // --- Core Hooking Logic ---
    private fun hookOomAdjuster(classLoader: ClassLoader) {
        val oomAdjusterClass = XposedHelpers.findClass("com.android.server.am.OomAdjuster", classLoader)

        // 1. The Decision Point
        XposedHelpers.findAndHookMethod(
            oomAdjusterClass, "computeOomAdjLSP",
            "com.android.server.am.ProcessRecord", Int::class.javaPrimitiveType, "com.android.server.am.ProcessRecord",
            Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val process = param.args[0] ?: return
                    val procState = XposedHelpers.getIntField(process, "mState.mCurProcState")

                    val decision = makeFreezeDecision(process, procState)
                    // Attach the decision to the process record for the next hook
                    XposedHelpers.setAdditionalInstanceField(process, DECISION_FIELD_NAME, decision)
                    
                    // We completely take over the logic, so prevent the original method from running
                    param.result = null
                }
            }
        )

        // 2. The Execution Point
        XposedHelpers.findAndHookMethod(
            oomAdjusterClass, "applyOomAdjLSP",
            "com.android.server.am.ProcessRecord", Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val process = param.args[0] ?: return
                    val decision = XposedHelpers.getAdditionalInstanceField(process, DECISION_FIELD_NAME) as? Decision ?: return
                    val isCurrentlyFrozen = XposedHelpers.getBooleanField(process, "mState.isFrozen()")

                    if (decision.shouldBeFrozen && !isCurrentlyFrozen) {
                        // Decision is to freeze, and it's not frozen yet
                        executeFreeze(process)
                    } else if (!decision.shouldBeFrozen && isCurrentlyFrozen) {
                        // Decision is to unfreeze, and it's currently frozen
                        executeUnfreeze(process)
                    }
                    
                    // We take over, so prevent original method
                    param.result = null
                }
            }
        )
        log("Hooked OomAdjuster successfully.")
    }
    
    private fun hookServices(classLoader: ClassLoader) {
        val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
        XposedBridge.hookAllConstructors(amsClass, object: XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val ams = param.thisObject
                powerManager = XposedHelpers.getObjectField(ams, "mPowerManager") as PowerManager
                log("Got PowerManager instance.")
            }
        })
    }
    
    // --- Decision Logic ---
    private fun makeFreezeDecision(process: Any, procState: Int): Decision {
        val appInfo = XposedHelpers.getObjectField(process, "info") as? android.content.pm.ApplicationInfo ?: return Decision(false)
        val uid = appInfo.uid
        val packageName = appInfo.packageName

        if (uid < Process.FIRST_APPLICATION_UID || packageName == "android") {
            return Decision(false)
        }
        
        // --- Pre-checks ---
        if (!ConfigManager.isEnabled()) return Decision(false)
        if (ConfigManager.isWhitelisted(packageName)) return Decision(false)
        if (ConfigManager.freezeOnScreenOff() && powerManager.isInteractive) return Decision(false)

        // --- Exemption Checks ---
        val isInteresting = XposedHelpers.callMethod(process, "isInterestingToUser") as Boolean
        if (isInteresting) return Decision(false) // Visible, perceptible, etc.
        
        if (ConfigManager.exemptForegroundServices() && XposedHelpers.callMethod(process, "hasForegroundServices") as Boolean) {
            return Decision(false)
        }

        // TODO: Implement more complex exemption checks (Audio, Camera, Overlay) by hooking respective services
        
        // --- Final Decision ---
        // The core logic: we freeze if the process is considered "cached" by the system
        val isCached = procState >= 15 // PROCESS_STATE_CACHED_ACTIVITY and above
        return Decision(isCached)
    }

    // --- Execution Logic ---
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

    // --- UDS Communication & Config Management ---
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
    
    // --- ConfigManager Singleton ---
    private object ConfigManager {
        private var config: FullConfigPayload? = null
        private val gson = Gson()

        fun updateConfig(json: String) {
            try {
                val type = object : TypeToken<CerberusMessage<FullConfigPayload>>() {}.type
                val msg = gson.fromJson<CerberusMessage<FullConfigPayload>>(json, type)
                config = msg.payload
                log("Probe config updated successfully.")
            } catch (e: Exception) {
                logError("Failed to parse probe config: $e")
            }
        }
        
        fun isEnabled(): Boolean = config?.masterConfig?.isEnabled ?: false
        fun freezeOnScreenOff(): Boolean = config?.masterConfig?.freezeOnScreenOff ?: true
        fun isWhitelisted(pkg: String): Boolean = config?.policies?.any { it.packageName == pkg && it.policy == 0 } ?: true
        fun exemptForegroundServices(): Boolean = config?.exemptConfig?.exemptForegroundServices ?: true
    }
    
    // --- Logging ---
    private fun log(message: String) = XposedBridge.log("$TAG: $message")
    private fun logError(message: String) = XposedBridge.log("$TAG: [ERROR] $message")
}