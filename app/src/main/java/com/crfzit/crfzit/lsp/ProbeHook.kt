// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.content.Intent
import android.content.pm.ApplicationInfo
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

/**
 * Project Cerberus - System Probe (v3.2, Robust Thaw)
 */
class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(Executors.newCachedThreadPool().asCoroutineDispatcher() + SupervisorJob())
    private val udsClient = UdsClient(probeScope)
    private val gson = Gson()

    // StateFlow确保新订阅者能立即获取最新状态，对我们的同步等待至关重要
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
        // 其他 hook 保持不变
        hookAppErrors(lpparam.classLoader)
        hookPowerManagerService(lpparam.classLoader)
        log("Probe v3.2 attachment complete.")
    }

    private fun setupUdsCommunication() {
        udsClient.start()

        probeScope.launch(Dispatchers.IO) {
            // 延时一下等daemon启动
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
                    // [FIX] 不再需要resp.unfreeze_complete，因为我们直接观察frozenAppsCache的变化
                } catch (e: Exception) {
                    logError("Error processing message from daemon: $e. JSON: $jsonLine")
                }
            }
        }
    }

    private fun hookActivityManagerService(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)

            // [FIX] 重构为同步阻塞解冻模型
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
                        
                        // 检查是否在我们已知的冻结列表中
                        if (frozenAppsCache.value.contains(key)) {
                            log("Gatekeeper: Intercepted start request for frozen app $key. Attempting synchronous thaw.")
                            
                            // 在当前线程（通常是Binder线程）上阻塞并执行解冻
                            val thawSuccess = runBlocking {
                                try {
                                    // 1. 发送解冻请求
                                    sendToDaemon("cmd.request_immediate_unfreeze", key)
                                    log("Thaw request sent for $key. Waiting for confirmation...")

                                    // 2. 等待 frozenAppsCache 更新，表示解冻已完成
                                    // withTimeout会在此协程作用域内等待，直到条件满足或超时
                                    withTimeout(THAW_TIMEOUT_MS) {
                                        // first {} 会挂起直到流中的第一个元素满足条件
                                        frozenAppsCache.first { updatedCache ->
                                            !updatedCache.contains(key)
                                        }
                                    }
                                    log("Thaw for $key confirmed by cache update.")
                                    true // 解冻成功
                                } catch (e: TimeoutCancellationException) {
                                    logError("Thaw confirmation for $key timed out after ${THAW_TIMEOUT_MS}ms.")
                                    false // 解冻失败
                                } catch (e: Exception) {
                                    logError("Error during thaw wait for $key: ${e.message}")
                                    false
                                }
                            }
                            
                            if (thawSuccess) {
                                log("Thaw successful for $key. Allowing original method to proceed.")
                                // 不需要再做任何事，hook会自动执行原始方法
                            } else {
                                logError("Thaw failed for $key. App will likely ANR. Allowing original method to proceed anyway.")
                                // 即使失败，也继续执行，让系统处理后续的ANR
                            }
                        }
                    }
                }
            )
            
            // oomAdj hook 保持不变
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

            log("Successfully hooked AMS for state sensing and robust preemptive thaw.")
        } catch (t: Throwable) {
            logError("Failed to hook ActivityManagerService: $t")
        }
    }
    
    // ANR干预逻辑，现在变得更重要，因为我们可能会因为等待解冻而导致ANR
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
                     
                     // 如果ANR是因为输入事件超时，并且我们知道这个应用刚刚被我们解冻，就压制ANR对话框
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

    // PowerManagerService hook 保持不变
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

    // sendToDaemon 保持不变
    private fun sendToDaemon(type: String, payload: Any) {
        // 使用 probeScope 启动协程以避免阻塞当前线程
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