// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.os.PowerManager
import android.os.Process
import com.crfzit.crfzit.data.model.AppInstanceKey
import com.crfzit.crfzit.data.model.CerberusMessage
import com.crfzit.crfzit.data.model.FullConfigPayload
import com.crfzit.crfzit.data.model.ProbeFreezePayload
import com.crfzit.crfzit.data.model.ProbeUnfreezePayload
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

@OptIn(DelicateCoroutinesApi::class)
class ProbeHook : IXposedHookLoadPackage {

    private val scope = GlobalScope
    private var udsClient: UdsClient? = null
    private val gson = Gson()
    private var powerManager: PowerManager? = null
    private val backgroundJobs = ConcurrentHashMap<AppInstanceKey, Job>()

    companion object {
        private const val TAG = "CerberusProbe_v22.2_FinalFix"
        private const val PER_USER_RANGE = 100000
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") {
            log("Loading into system_server (PID: ${Process.myPid()}).")

            val singleThreadContext = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

            udsClient = UdsClient(scope)

            scope.launch(singleThreadContext) {
                setupPersistentUdsCommunication()
            }

            try {
                hookServices(lpparam.classLoader)
                hookOomAdjusterCoreMethod(lpparam.classLoader)
            } catch (t: Throwable) {
                logError("CRITICAL: Failed during hook placement: $t")
            }
        }
    }

    private fun hookServices(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
            XposedBridge.hookAllConstructors(amsClass, object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (powerManager == null) {
                        powerManager = XposedHelpers.getObjectField(param.thisObject, "mPowerManager") as? PowerManager
                        if (powerManager != null) log("Successfully got PowerManager instance.")
                        else logError("Failed to get PowerManager instance from AMS.")
                    }
                }
            })
            log("Hook placed on AMS constructors to get PowerManager.")
        } catch (t: Throwable) {
            logError("Error hooking AMS constructor: $t")
        }
    }

    private fun hookOomAdjusterCoreMethod(classLoader: ClassLoader) {
        try {
            // We now know the exact class and method from our investigation.
            val targetClassName = "com.android.server.am.OomAdjusterModernImpl"
            val targetMethodName = "performUpdateOomAdjLSP"

            val oomAdjusterClass = XposedHelpers.findClass(targetClassName, classLoader)
            val processRecordClass = XposedHelpers.findClass("com.android.server.am.ProcessRecord", classLoader)

            XposedHelpers.findAndHookMethod(
                oomAdjusterClass,
                targetMethodName,
                processRecordClass,
                Int::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val process = param.args[0] ?: return
                        try {
                            makeAndExecuteFreezeDecision(process)
                            // Hijack the original method by setting a result.
                            // The actual return value (true/false) doesn't matter much in this case.
                            param.result = true
                        } catch (e: Throwable) {
                            logError("Error in $targetMethodName hook: $e")
                        }
                    }
                }
            )
            log("SUCCESS! Directly hooked the target method: $targetClassName#$targetMethodName")
        } catch (e: XposedHelpers.ClassNotFoundError) {
            logError("Target class not found: ${e.message}. This ROM may not be supported.")
        } catch (e: NoSuchMethodError) {
            logError("Target method not found: ${e.message}. This ROM may not be supported.")
        } catch (t: Throwable) {
            logError("An unexpected error occurred while hooking the core method: $t")
        }
    }

    private fun makeAndExecuteFreezeDecision(process: Any) {
        val appInfo = XposedHelpers.getObjectField(process, "info") as? android.content.pm.ApplicationInfo ?: return
        val uid = appInfo.uid
        val packageName = appInfo.packageName
        if (uid < Process.FIRST_APPLICATION_UID || packageName == "android") return

        val userId = uid / PER_USER_RANGE
        val key = AppInstanceKey(packageName, userId)
        val policy = ConfigManager.getPolicy(packageName, userId)

        val isInteresting = XposedHelpers.callMethod(process, "isInterestingToUser") as Boolean
        val hasFgs = XposedHelpers.callMethod(process, "hasForegroundServices") as Boolean
        val screenOn = powerManager?.isInteractive ?: true

        val shouldBeExempt = isInteresting ||
                !ConfigManager.isEnabled() ||
                policy == 0 || // EXEMPTED
                (ConfigManager.exemptForegroundServices() && hasFgs) ||
                (!ConfigManager.freezeOnScreenOff() || screenOn)

        if (shouldBeExempt) {
            // If the app becomes exempt, cancel any pending freeze job and ensure it's unfrozen.
            backgroundJobs.remove(key)?.let {
                it.cancel()
                log("Process '$packageName' (user: $userId) became exempt. Cancelling timer and executing UNFREEZE.")
                executeUnfreeze(process)
            }
        } else {
            // If the app is a freeze candidate, start a timer if one isn't already running.
            if (backgroundJobs[key]?.isActive != true) {
                val timeout = when (policy) {
                    3 -> 15_000L // Strict: 15 seconds
                    2 -> 90_000L // Standard: 90 seconds
                    else -> return // Other policies don't have a freeze timer
                }
                log("App '$packageName' (user: $userId) is a freeze candidate. Starting ${timeout/1000}s timer.")
                backgroundJobs[key] = scope.launch {
                    try {
                        delay(timeout)
                        log("Timeout reached for '$packageName'. Executing FREEZE.")
                        executeFreeze(process)
                    } catch (_: CancellationException) {
                        // This is a normal outcome if the job is cancelled.
                    } finally {
                        // Clean up the job from the map once it's done or cancelled.
                        backgroundJobs.remove(key)
                    }
                }
            }
        }
    }

    private suspend fun setupPersistentUdsCommunication() {
        log("Persistent communication manager started.")
        while (scope.isActive) {
            try {
                log("Starting UDS client connection cycle...")
                udsClient?.start() // This is a non-blocking call that starts the internal connection loop.

                delay(5000) // Initial delay before the first hello.

                log("Sending hello to daemon.")
                sendToDaemon("event.probe_hello", mapOf("pid" to Process.myPid(), "version" to TAG))

                // This collect will suspend until the connection is lost.
                udsClient?.incomingMessages?.collect { jsonLine: String ->
                    try {
                        ConfigManager.updateConfig(jsonLine)
                    } catch (e: Exception) {
                        logError("Error processing config message: $e")
                    }
                }

                // If collect returns, it means the connection was lost.
                logError("UDS message stream ended. Connection likely lost. Restarting cycle.")
                udsClient?.stop()

            } catch (e: Exception) {
                logError("Exception in communication cycle: $e. Restarting.")
            }

            // Wait before attempting to restart the entire cycle.
            delay(10000L)
        }
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
    }

    private fun sendToDaemon(type: String, payload: Any) {
        scope.launch {
            try {
                val message = CerberusMessage(type = type, payload = payload)
                udsClient?.sendMessage(gson.toJson(message))
            } catch (e: Exception) {
                logError("Daemon send error: $e")
            }
        }
    }

    private object ConfigManager {
        @Volatile private var config: FullConfigPayload? = null
        private val gson = Gson()
        private val jsonParser = JsonParser()

        fun updateConfig(jsonString: String) {
            try {
                val root = jsonParser.parse(jsonString).asJsonObject
                if (root.has("payload")) {
                    val payloadObject = root.getAsJsonObject("payload")
                    config = gson.fromJson(payloadObject, FullConfigPayload::class.java)
                    XposedBridge.log("$TAG: Probe config updated successfully. Enabled: ${config?.masterConfig?.isEnabled}")
                } else {
                    XposedBridge.log("$TAG: [ERROR] Received config message without payload field. JSON: $jsonString")
                }
            } catch (e: Exception) {
                XposedBridge.log("$TAG: [ERROR] Failed to parse probe config: $e. JSON: $jsonString")
            }
        }

        // Default to 'false' to ensure nothing happens until a valid config is received.
        fun isEnabled(): Boolean = config?.masterConfig?.isEnabled ?: false
        fun freezeOnScreenOff(): Boolean = config?.masterConfig?.freezeOnScreenOff ?: false
        fun exemptForegroundServices(): Boolean = config?.exemptConfig?.exemptForegroundServices ?: true
        fun getPolicy(pkg: String, userId: Int): Int {
            return config?.policies?.find { it.packageName == pkg && it.userId == userId }?.policy ?: 2 // Default to Standard
        }
    }

    private fun log(message: String) = XposedBridge.log("[$TAG] $message")
    private fun logError(message: String) = XposedBridge.log("[$TAG] [ERROR] $message")
}