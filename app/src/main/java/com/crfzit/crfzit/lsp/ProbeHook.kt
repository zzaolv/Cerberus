// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import com.crfzit.crfzit.data.model.*
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Project Cerberus - System Probe (v3.0, Preemptive Thaw & ANR Intervention)
 * 职责:
 * 1. [感知] Hook Framework API，感知应用和系统状态变化。
 * 2. [抢占] 完全接管对已冻结应用的启动请求，实现先解冻、后启动的可靠流程。
 * 3. [干预] 阻止系统对正在解冻的应用弹出ANR对话框或执行ANR查杀。
 * 4. [协作] 与Daemon进行双向通信。
 */
class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(Executors.newCachedThreadPool().asCoroutineDispatcher() + SupervisorJob())
    private val udsClient = UdsClient(probeScope)
    private val gson = Gson()

    private val frozenAppsCache = MutableStateFlow<Set<AppInstanceKey>>(emptySet())
    // 存储正在进行解冻的异步任务，用于ANR干预检查
    private val pendingUnfreezeJobs = ConcurrentHashMap<AppInstanceKey, CompletableFuture<Boolean>>()

    companion object {
        private const val TAG = "CerberusProbe_v3.0"
        private const val PER_USER_RANGE = 100000
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" || lpparam.processName != "android") {
            return
        }
        log("Attaching to system_server (PID: ${Process.myPid()}). Probe v3.0 is activating...")
        setupUdsCommunication()
        hookActivityManagerService(lpparam.classLoader)
        hookAppErrors(lpparam.classLoader) // ANR干预
        hookPowerManagerService(lpparam.classLoader)
        log("Probe v3.0 attachment complete.")
    }

    private fun setupUdsCommunication() {
        udsClient.start()

        probeScope.launch {
            delay(2000)
            sendToDaemon("event.probe_hello", mapOf("version" to "3.0.0", "pid" to Process.myPid()))

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
                        "resp.unfreeze_complete" -> {
                            val type = object : TypeToken<CerberusMessage<AppInstanceKey>>() {}.type
                            val msg = gson.fromJson<CerberusMessage<AppInstanceKey>>(jsonLine, type)
                            log("Received unfreeze confirmation for ${msg.payload.packageName}")
                            // 标记对应的异步任务已完成
                            pendingUnfreezeJobs.remove(msg.payload)?.complete(true)
                        }
                    }
                } catch (e: Exception) {
                    logError("Error processing message from daemon: $e")
                }
            }
        }
    }

    private fun hookActivityManagerService(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)

            // 使用 XC_MethodReplacement 完全接管 startActivityAsUser
            // 这是 startActivity 的最终调用点，Hook这里最有效
            XposedHelpers.findAndHookMethod(
                amsClass, "startActivityAsUser",
                "android.app.IApplicationThread", // caller
                String::class.java, // callingPackage
                Intent::class.java, // intent
                String::class.java, // resolvedType
                "android.os.IBinder", // resultTo
                String::class.java, // resultWho
                Int::class.javaPrimitiveType, // requestCode
                Int::class.javaPrimitiveType, // flags
                "android.app.ProfilerInfo", // profilerInfo
                "android.os.Bundle", // options
                Int::class.javaPrimitiveType, // userId
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        val intent = param.args[2] as Intent
                        val userId = param.args[10] as Int
                        val component = intent.component ?: return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                        
                        val key = AppInstanceKey(component.packageName, userId)

                        // 核心逻辑：如果是冻结应用，则执行“抢占式解冻”
                        if (frozenAppsCache.value.contains(key)) {
                            log("Gatekeeper: Intercepted start request for frozen app $key. Starting preemptive thaw.")
                            
                            // 启动异步解冻并重新启动的工作流
                            probeScope.launch {
                                val future = CompletableFuture<Boolean>()
                                pendingUnfreezeJobs[key] = future
                                
                                sendToDaemon("cmd.request_immediate_unfreeze", key)

                                try {
                                    // 等待Daemon的确认，设置超时
                                    withTimeout(4500) { // 4.5秒超时，略小于系统ANR时间
                                        future.get(4500, TimeUnit.MILLISECONDS)
                                    }
                                    log("Thaw confirmed for $key. Re-invoking original startActivity.")
                                    // 成功解冻后，重新调用原始的startActivity
                                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                                } catch (e: Exception) {
                                    logError("Failed to thaw $key in time: ${e.message}. ANR intervention may be needed.")
                                    // 即使超时，也尝试再次调用，让ANR干预机制来处理
                                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                                } finally {
                                    pendingUnfreezeJobs.remove(key)
                                }
                            }
                            
                            // “欺骗”AMS，立即返回成功，避免阻塞system_server
                            return ActivityManager.START_SUCCESS
                        }

                        // 如果不是冻结应用，则正常执行
                        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                    }
                }
            )
            
            // Hook 应用进程状态变化（如oom_adj），这是感知前后台的补充
            XposedHelpers.findAndHookMethod(
                "com.android.server.am.ProcessList", classLoader, "updateOomAdj",
                "com.android.server.am.ProcessRecord",
                Boolean::class.javaPrimitiveType, // knownToBeForeground
                "com.android.server.am.OomAdjuster",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val processRecord = param.args[0]
                            val appInfo = XposedHelpers.getObjectField(processRecord, "info") as ApplicationInfo
                            val packageName = appInfo.packageName
                            val userId = XposedHelpers.getIntField(processRecord, "userId")
                            val oomAdj = XposedHelpers.getIntField(processRecord, "mState.mCurAdj")

                            val isForeground = oomAdj < 100 

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
            
            // Hook "应用无响应" 对话框的显示
            XposedHelpers.findAndHookMethod(appErrorsClass, "handleShowAnrUi", "com.android.server.am.ProcessRecord", String::class.java, object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val processRecord = param.args[0]
                    val packageName = XposedHelpers.getObjectField(processRecord, "info")?.let {
                        (it as android.content.pm.ApplicationInfo).packageName
                    } ?: return
                    val userId = XposedHelpers.getIntField(processRecord, "userId")
                    
                    val key = AppInstanceKey(packageName, userId)
                    
                    // 如果ANR的应用是我们正在解冻的，则阻止弹窗
                    if (pendingUnfreezeJobs.containsKey(key)) {
                        log("ANR Intervention: Suppressing ANR dialog for thawing app $key.")
                        param.result = null // 阻止原始方法执行
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
            
            // Hook goToSleep (息屏)
            XposedHelpers.findAndHookMethod(pmsClass, "goToSleep", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, object : XC_MethodHook(){
                override fun afterHookedMethod(param: MethodHookParam?) {
                    log("Event: System is going to sleep (screen off).")
                    sendToDaemon("event.system_state_changed", ProbeSystemStateChangedPayload(screenOn = false))
                }
            })
            
            // Hook wakeUp (亮屏)
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
                val message = CerberusMessage(3, type, null, payload)
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