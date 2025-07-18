// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import com.crfzit.crfzit.data.model.*
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
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

/**
 * Project Cerberus - System Probe (v3.1, Reliable Thaw)
 */
class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(Executors.newCachedThreadPool().asCoroutineDispatcher() + SupervisorJob())
    private val udsClient = UdsClient(probeScope)
    private val gson = Gson()

    // [FIX] 使用 StateFlow 来确保新订阅者能立即获取最新状态
    private val frozenAppsCache = MutableStateFlow<Set<AppInstanceKey>>(emptySet())
    private val pendingUnfreezeJobs = ConcurrentHashMap<AppInstanceKey, CompletableFuture<Boolean>>()

    companion object {
        private const val TAG = "CerberusProbe_v3.1"
    }
    
    // 定义一个简单的响应模型来解析解冻结果
    data class UnfreezeResponsePayload(
        @SerializedName("package_name") val packageName: String,
        @SerializedName("user_id") val userId: Int,
        val success: Boolean
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" || lpparam.processName != "android") {
            return
        }
        log("Attaching to system_server (PID: ${Process.myPid()}). Probe v3.1 is activating...")
        setupUdsCommunication()
        hookActivityManagerService(lpparam.classLoader)
        hookAppErrors(lpparam.classLoader)
        hookPowerManagerService(lpparam.classLoader)
        log("Probe v3.1 attachment complete.")
    }

    private fun setupUdsCommunication() {
        udsClient.start()

        probeScope.launch(Dispatchers.IO) {
            // 延时一下等daemon启动
            delay(2000) 
            sendToDaemon("event.probe_hello", mapOf("version" to "3.1.0", "pid" to Process.myPid()))

            udsClient.incomingMessages.collectLatest { jsonLine ->
                try {
                    val baseMsg = gson.fromJson(jsonLine, CerberusMessage::class.java)
                    when (baseMsg.type) {
                        "stream.probe_config_update" -> {
                            val type = object : TypeToken<CerberusMessage<ProbeConfigUpdatePayload>>() {}.type
                            val msg = gson.fromJson<CerberusMessage<ProbeConfigUpdatePayload>>(jsonLine, type)
                            val newCache = msg.payload.frozenApps.toSet()
                            if (frozenAppsCache.value != newCache) {
                                frozenAppsCache.value = newCache
                                log("Frozen app cache updated. Size: ${newCache.size}")
                            }
                        }
                        // [FIX #4] 正确处理来自daemon的解冻完成响应
                        "resp.unfreeze_complete" -> {
                            val type = object : TypeToken<CerberusMessage<UnfreezeResponsePayload>>() {}.type
                            val msg = gson.fromJson<CerberusMessage<UnfreezeResponsePayload>>(jsonLine, type)
                            val key = AppInstanceKey(msg.payload.packageName, msg.payload.userId)
                            log("Received unfreeze confirmation for $key. Success: ${msg.payload.success}")
                            pendingUnfreezeJobs.remove(key)?.complete(msg.payload.success)
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
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val intent = param.args[2] as Intent
                        val userId = param.args[10] as Int
                        val component = intent.component ?: return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                        
                        val key = AppInstanceKey(component.packageName, userId)

                        if (frozenAppsCache.value.contains(key)) {
                            log("Gatekeeper: Intercepted start request for frozen app $key. Starting preemptive thaw.")
                            
                            probeScope.launch {
                                val future = CompletableFuture<Boolean>()
                                pendingUnfreezeJobs[key] = future
                                
                                sendToDaemon("cmd.request_immediate_unfreeze", key)

                                try {
                                    val unfreezeSuccess = withTimeout(4500) { future.get() }
                                    
                                    if(unfreezeSuccess) {
                                        log("Thaw confirmed for $key. Re-invoking original startActivity.")
                                        XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                                    } else {
                                        logError("Daemon reported thaw FAILED for $key. Aborting start.")
                                    }

                                } catch (e: Exception) {
                                    logError("Failed to thaw $key in time: ${e.message}. The app will likely ANR. Calling original method anyway.")
                                    // 超时后，我们无能为力，只能让系统去处理，ANR干预机制可能会起作用
                                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                                } finally {
                                    pendingUnfreezeJobs.remove(key)
                                }
                            }
                            
                            // 立即返回成功，避免阻塞system_server
                            return 0
                        }

                        // 如果不是冻结应用，则正常执行
                        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
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
                            val isForeground = oomAdj < 200 // OOM_ADJ_PERCEPTIBLE_APP as threshold

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

            log("Successfully hooked AMS for state sensing and preemptive thaw.")
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
                    val packageName = XposedHelpers.getObjectField(processRecord, "info")?.let {
                        (it as android.content.pm.ApplicationInfo).packageName
                    } ?: return
                    val userId = XposedHelpers.getIntField(processRecord, "userId")
                    
                    val key = AppInstanceKey(packageName, userId)
                    
                    if (pendingUnfreezeJobs.containsKey(key)) {
                        log("ANR Intervention: Suppressing ANR dialog for thawing app $key.")
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
                // [FIX] 增加请求ID，便于追踪
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