// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import com.crfzit.crfzit.data.model.AppInstanceKey
import com.crfzit.crfzit.data.model.CerberusMessage
import com.crfzit.crfzit.data.model.ProbeConfigUpdatePayload
import com.crfzit.crfzit.data.model.ProbeSystemStateChangedPayload
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
import java.util.concurrent.Executors

/**
 * Project Cerberus - System Probe (v2.2, Final Complete Version)
 * 职责：
 * 1. [感知] Hook 关键Framework API，感知应用和系统状态变化。
 * 2. [感知] 将所有感知到的变化格式化为标准JSON事件，发送给 `cerberusd`。
 * 3. [协作] 在启动应用时，若发现应用被冻结，立即以非阻塞方式向 `cerberusd` 发送解冻命令。
 * 4. [协作] 接收 `cerberusd` 的配置更新，维护一个轻量级的状态缓存。
 *
 * 原则：
 * - 绝对非阻塞：所有Hook点内的操作必须立即返回，避免影响 `system_server`。
 * - 职责单一：只做感知和转发，不做复杂决策。
 */
class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher() + SupervisorJob())
    private val udsClient = UdsClient(probeScope)
    private val gson = Gson()

    private val frozenAppsCache = MutableStateFlow<Set<AppInstanceKey>>(emptySet())

    companion object {
        private const val TAG = "CerberusProbe_v2.2"
        private const val PER_USER_RANGE = 100000
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" || lpparam.processName != "android") {
            return
        }
        log("Attaching to system_server (PID: ${Process.myPid()}). Probe v2.2 is activating...")
        setupUdsCommunication()
        hookActivityManagerService(lpparam.classLoader)
        hookPowerManagerService(lpparam.classLoader)
        log("Probe v2.2 attachment complete.")
    }

    private fun setupUdsCommunication() {
        udsClient.start()

        probeScope.launch {
            // 启动后，向Daemon问好
            delay(2000) 
            sendEventToDaemon(
                "event.probe_hello",
                mapOf("version" to "2.2.0", "pid" to Process.myPid())
            )

            // 持续监听来自Daemon的配置更新
            udsClient.incomingMessages.collectLatest { jsonLine ->
                try {
                    val baseMsg = gson.fromJson(jsonLine, CerberusMessage::class.java)
                    if (baseMsg.type == "stream.probe_config_update") {
                        val type = object : TypeToken<CerberusMessage<ProbeConfigUpdatePayload>>() {}.type
                        val msg = gson.fromJson<CerberusMessage<ProbeConfigUpdatePayload>>(jsonLine, type)
                        val newCache = msg.payload.frozenApps.toSet()
                        if (frozenAppsCache.value != newCache) {
                             frozenAppsCache.value = newCache
                             log("Received config update. Frozen apps cache size: ${frozenAppsCache.value.size}")
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

            // Hook 应用启动，这是最核心的协作点
            val startActivityHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                    val component = intent.component ?: return
                    val packageName = component.packageName
                    
                    // 获取调用者UID和目标用户ID，这是获取正确用户ID的可靠方式
                    // 不同安卓版本参数位置可能不同，需要健壮处理
                    var userId = -1
                    try {
                        val callerIdIndex = param.args.indexOfFirst { arg -> arg is String && arg == "startActivity" } - 4
                         if (callerIdIndex >= 0 && callerIdIndex + 2 < param.args.size) {
                             userId = XposedHelpers.callStaticMethod(ActivityManager::class.java, "handleIncomingUser", 
                                param.args[callerIdIndex],       // callingPid
                                param.args[callerIdIndex + 1],   // callingUid
                                param.args[callerIdIndex + 2],   // userId
                                false, false, "startActivity", null) as Int
                         }
                    } catch (t: Throwable) {
                         // Fallback for different Android versions or signature changes
                         logError("Could not determine userId from startActivity hook, falling back to user 0. Error: $t")
                         userId = 0
                    }

                    if (userId == -1) userId = 0 // Ensure userId is valid
                    
                    val key = AppInstanceKey(packageName, userId)

                    // 检查缓存，如果目标应用被冻结，非阻塞地请求解冻
                    if (frozenAppsCache.value.contains(key)) {
                        log("Collaborative Unfreeze: Attempting to start frozen app '$packageName' (User $userId). Firing immediate unfreeze command.")
                        // 调用完全非阻塞的 "fire-and-forget" 方法
                        requestImmediateUnfreeze(key)
                    }
                }
            }
            XposedBridge.hookAllMethods(amsClass, "startActivity", startActivityHook)
            
            // Hook 应用进程状态变化（如oom_adj），这是感知前后台的补充
            // updateOomAdj 是一个非常可靠的应用状态变化来源
            try {
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

                                // oom_adj < 100 是一个很好的前台或可感知指标
                                val isForeground = oomAdj < 100 

                                val payload = mapOf(
                                    "package_name" to packageName,
                                    "user_id" to userId,
                                    "is_foreground" to isForeground,
                                    "oom_adj" to oomAdj,
                                    "reason" to "updateOomAdj"
                                )
                                sendEventToDaemon("event.app_state_changed", payload)
                            } catch (t: Throwable) {
                                logError("Error inside updateOomAdj hook: $t")
                            }
                        }
                    }
                )
            } catch (e: NoSuchMethodError) {
                logError("Failed to hook ProcessList.updateOomAdj, method not found. This might be due to Android version differences.")
            }


            log("Successfully hooked AMS methods for app state and collaborative unfreeze.")
        } catch (t: Throwable) {
            logError("Failed to hook ActivityManagerService: $t")
        }
    }
    
    /**
     * [完整实现] Hook电源管理服务以感知亮屏和息屏事件。
     */
    private fun hookPowerManagerService(classLoader: ClassLoader) {
        try {
            val pmsClass = XposedHelpers.findClass("com.android.server.power.PowerManagerService", classLoader)
            
            // Hook goToSleep (息屏)
            // Signature is consistent across many Android versions
            XposedHelpers.findAndHookMethod(pmsClass, "goToSleep", 
                Long::class.javaPrimitiveType, // eventTime
                Int::class.javaPrimitiveType,  // reason
                Int::class.javaPrimitiveType,  // flags
                object : XC_MethodHook(){
                override fun afterHookedMethod(param: MethodHookParam?) {
                    log("Event: System is going to sleep (screen off).")
                    sendEventToDaemon("event.system_state_changed", ProbeSystemStateChangedPayload(screenOn = false))
                }
            })
            
            // Hook wakeUp (亮屏)
            // This signature can vary, so we find a common one
            XposedHelpers.findAndHookMethod(pmsClass, "wakeUp", 
                Long::class.javaPrimitiveType, // eventTime
                Int::class.javaPrimitiveType,  // reason
                String::class.java,            // details
                String::class.java,            // opPackageName
                Int::class.javaPrimitiveType,  // opUid
                Int::class.javaPrimitiveType,  // opPid
                object : XC_MethodHook(){
                override fun afterHookedMethod(param: MethodHookParam?) {
                    log("Event: System is waking up (screen on).")
                    sendEventToDaemon("event.system_state_changed", ProbeSystemStateChangedPayload(screenOn = true))
                }
            })
            
            log("Successfully hooked PMS methods for screen state.")
        } catch (t: Throwable) {
             logError("Failed to hook PowerManagerService: $t. This might not be a critical error on some devices.")
        }
    }

    /**
     * 向Daemon发送立即解冻命令（非阻塞，Fire-and-Forget）
     */
    private fun requestImmediateUnfreeze(key: AppInstanceKey) {
        val requestId = "unfreeze-cmd-${System.currentTimeMillis()}"
        val message = CerberusMessage(2, "cmd.request_immediate_unfreeze", requestId, key)
        sendEventToDaemon(message.type, message.payload, message.requestId)
    }
    
    /**
     * 通用事件发送函数，支持可选的requestId
     */
    private fun sendEventToDaemon(type: String, payload: Any, requestId: String? = null) {
        try {
            val message = CerberusMessage(2, type, requestId, payload)
            val jsonString = gson.toJson(message)
            // 在协程中发送，不阻塞当前Hook线程
            probeScope.launch {
                udsClient.sendMessage(jsonString)
            }
        } catch (e: Exception) {
            logError("Error sending event '$type' to daemon: ${e.message}")
        }
    }

    private fun log(message: String) = XposedBridge.log("$TAG: $message")
    private fun logError(message: String) = XposedBridge.log("$TAG: [ERROR] $message")
}