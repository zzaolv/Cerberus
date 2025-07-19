// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.content.pm.ApplicationInfo
import android.os.Process
import com.crfzit.crfzit.data.model.*
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Project Cerberus - System Probe (v3.4, Backend-Driven Unfreeze with ANR Intervention)
 */
class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(Executors.newCachedThreadPool().asCoroutineDispatcher() + SupervisorJob())
    private val udsClient = UdsClient(probeScope)
    private val gson = Gson()
    
    // 用于记录最近被提升到前台的应用，以便ANR Hook进行判断
    private val recentlyPromotedApps = ConcurrentHashMap<String, Long>()
    private val anrCheckWindowMs = 5000L // 5秒的ANR检查窗口期

    companion object {
        private const val TAG = "CerberusProbe_v3.4"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" || lpparam.processName != "android") {
            return
        }
        log("Attaching to system_server (PID: ${Process.myPid()}). Probe v3.4 is activating...")
        setupUdsCommunication()
        
        hookOomAdjUpdates(lpparam.classLoader)
        hookPowerManagerService(lpparam.classLoader)
        // [FIX] 重新引入 ANR 干预 Hook
        hookAppErrors(lpparam.classLoader)
        
        log("Probe v3.4 attachment complete. Using backend-driven unfreeze model with ANR intervention.")
    }

    private fun setupUdsCommunication() {
        udsClient.start()
        probeScope.launch(Dispatchers.IO) {
            delay(5000)
            sendToDaemon("event.probe_hello", mapOf("version" to "3.4.0", "pid" to Process.myPid()))
        }
    }
    
    private fun hookOomAdjUpdates(classLoader: ClassLoader) {
        try {
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
                            val packageName = appInfo.packageName ?: return
                            val userId = XposedHelpers.getIntField(processRecord, "userId")
                            val oomAdj = XposedHelpers.getIntField(processRecord, "mState.mCurAdj")
                            val isForeground = oomAdj < 200

                            // 如果一个应用被提升到前台，记录下这个事件
                            if (isForeground) {
                                recentlyPromotedApps["$packageName:$userId"] = System.currentTimeMillis()
                            }

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
            log("Successfully hooked ProcessList.updateOomAdj for state sensing.")
        } catch(t: Throwable) {
            logError("Failed to hook ProcessList.updateOomAdj: $t")
        }
    }

    // [FIX] 重新实现 ANR 干预逻辑
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
                    val userId = XposedHelpers.getIntField(processRecord, "userId")
                    val key = "$packageName:$userId"

                    val promotionTime = recentlyPromotedApps[key]
                    if (promotionTime != null) {
                        val timeSincePromotion = System.currentTimeMillis() - promotionTime
                        // 如果 ANR 发生在应用被提升到前台后的5秒内，我们有理由相信这是解冻延迟引起的
                        if (timeSincePromotion < anrCheckWindowMs) {
                             log("ANR Intervention: Suppressing ANR dialog for recently promoted app $key. Reason: $anrReason")
                             // 阻止显示 ANR 对话框
                             param.result = null 
                             // 从记录中移除，避免重复处理
                             recentlyPromotedApps.remove(key)
                        }
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