// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.content.pm.ApplicationInfo
import android.os.PowerManager
import android.os.Process
import com.crfzit.crfzit.data.model.CerberusMessage
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
import kotlin.collections.ArrayList

@OptIn(DelicateCoroutinesApi::class)
class ProbeHook : IXposedHookLoadPackage {

    private val scope = GlobalScope
    private var udsClient: UdsClient? = null
    private val gson = Gson()

    // [V5修复] PowerManager实例现在是可空类型，并在更早的时机获取
    @Volatile
    private var powerManager: PowerManager? = null

    private val foregroundStatusCache = ConcurrentHashMap<Int, Boolean>()

    companion object {
        private const val TAG = "CerberusProbe_v5_Universal"
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
                // [V5修复] 采用全新的、更具兼容性的Hook策略
                hookSystemServerForCoreObjects(lpparam.classLoader)
                hookAMSForProcessStateChanges(lpparam.classLoader) // 终极Hook点
                hookWakelocksAndAlarms(lpparam.classLoader)
            } catch (t: Throwable) {
                logError("CRITICAL: Failed during hook placement: $t")
            }
        }
    }

    // 策略1: 在系统服务启动的早期就Hook，以确保能捕获到PowerManager实例
    private fun hookSystemServerForCoreObjects(classLoader: ClassLoader) {
        try {
            val systemServerClass = XposedHelpers.findClass("com.android.server.SystemServer", classLoader)
            // Hook startOtherServices，这是大多数服务（包括AMS）完成初始化的阶段
            XposedBridge.hookAllMethods(systemServerClass, "startOtherServices", object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (powerManager == null) {
                        try {
                            val pm = XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("android.os.ServiceManager", classLoader),
                                "getService", "power"
                            )
                            if (pm != null) {
                                // 我们拿到的是Binder代理，需要找到实际的Service实例
                                val pmsClass = XposedHelpers.findClass("com.android.server.power.PowerManagerService", classLoader)
                                powerManager = XposedHelpers.findFirstFieldByExactType(pmsClass, PowerManager::class.java).get(null) as? PowerManager
                                log("Successfully got PowerManager instance via ServiceManager.")
                            }
                        } catch (t: Throwable) {
                            logError("Failed to get PowerManager via ServiceManager fallback: $t")
                        }
                    }
                }
            })
        } catch (t: Throwable) {
            logError("Failed to hook SystemServer for core objects: $t")
        }
    }

    // 策略2: 终极进程状态Hook - 广撒网，Hook所有可能改变进程状态的方法
    private fun hookAMSForProcessStateChanges(classLoader: ClassLoader) {
        try {
            val amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
            val processRecordClass = XposedHelpers.findClass("com.android.server.am.ProcessRecord", classLoader)

            var hookedMethodCount = 0
            // 扫描AMS中所有第一个参数是ProcessRecord的方法
            amsClass.declaredMethods.filter {
                it.parameterTypes.firstOrNull() == processRecordClass
            }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val processRecord = param.args[0] as Any
                        handleProcessStateChange(processRecord)
                    }
                })
                hookedMethodCount++
            }
            if (hookedMethodCount > 0) {
                log("SUCCESS: Universal hook placed on $hookedMethodCount AMS methods accepting ProcessRecord.")
            } else {
                logError("Universal hook on AMS failed, no suitable methods found.")
            }
        } catch (t: Throwable) {
            logError("Failed to place universal hook on AMS: $t")
        }
    }

    // 统一的状态处理逻辑
    private fun handleProcessStateChange(processRecord: Any) {
        val appInfo = XposedHelpers.getObjectField(processRecord, "info") as? ApplicationInfo ?: return
        val uid = appInfo.uid
        if (uid < Process.FIRST_APPLICATION_UID) return

        // 核心判断逻辑保持不变，但现在由更可靠的Hook点触发
        val adj = XposedHelpers.getIntField(processRecord, "mCurAdj")
        val procState = XposedHelpers.getIntField(processRecord, "mCurProcState")

        // 可感知的adj值通常<=200，进程状态为TOP是2
        val isForeground = adj <= 200 || procState == 2

        val lastStatus = foregroundStatusCache[uid]
        if (lastStatus == isForeground) return

        foregroundStatusCache[uid] = isForeground
        val packageName = appInfo.packageName
        val userId = uid / PER_USER_RANGE

        if (isForeground) {
            log("EVENT: App became foreground: $packageName (user: $userId, adj: $adj, state: $procState)")
            sendEventToDaemon("event.app_foreground", mapOf("package_name" to packageName, "user_id" to userId))
        } else {
            log("EVENT: App became background: $packageName (user: $userId, adj: $adj, state: $procState)")
            sendEventToDaemon("event.app_background", mapOf("package_name" to packageName, "user_id" to userId))
        }
    }

    // 策略3：唤醒拦截逻辑保持动态扫描，增强健壮性
    private fun hookWakelocksAndAlarms(classLoader: ClassLoader) {
        try {
            val pmsClass = XposedHelpers.findClass("com.android.server.power.PowerManagerService", classLoader)
            pmsClass.declaredMethods.filter { it.name == "acquireWakeLockInternal" }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val uidIndex = param.args.indexOfFirst { it is Int && it >= Process.FIRST_APPLICATION_UID }
                        if (uidIndex == -1) return
                        val uid = param.args[uidIndex] as Int

                        if (ConfigManager.isUidFrozen(uid)) {
                            val pkgIndex = param.args.indexOfFirst { it is String && (it as String).contains('.') }
                            val packageName = if(pkgIndex != -1) param.args[pkgIndex] as String else "unknown"
                            log("DEFENSE: Blocked acquireWakeLock for frozen app: $packageName (uid: $uid)")
                            param.result = null
                        }
                    }
                })
            }
            log("Dynamic hook placed on all overloads of PowerManagerService#acquireWakeLockInternal.")
        } catch (t: Throwable) {
            logError("Failed to hook PowerManagerService: $t")
        }

        try {
            val amsClass = XposedHelpers.findClass("com.android.server.alarm.AlarmManagerService", classLoader)
            XposedBridge.hookAllMethods(amsClass, "triggerAlarmsLocked", object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val triggerList = param.args.firstOrNull { it is ArrayList<*> } as? ArrayList<*> ?: return
                    triggerList.removeIf { alarm ->
                        val uid = XposedHelpers.getIntField(alarm!!, "uid")
                        if (ConfigManager.isUidFrozen(uid)) {
                            val tag = XposedHelpers.getObjectField(alarm, "mTag") as? String ?: XposedHelpers.getObjectField(alarm, "statsTag") as? String ?: "unknown"
                            log("DEFENSE: Blocked alarm ($tag) for frozen app uid: $uid")
                            true
                        } else {
                            false
                        }
                    }
                }
            })
            log("Dynamic hook placed on all overloads of AlarmManagerService#triggerAlarmsLocked.")
        } catch (t: Throwable) {
            logError("Failed to hook AlarmManagerService: $t")
        }
    }

    // (UDS通信, ConfigManager等辅助代码保持不变)
    private suspend fun setupPersistentUdsCommunication() {
        log("Persistent communication manager started.")
        while (scope.isActive) {
            try {
                udsClient?.start()
                delay(1000)
                sendEventToDaemon("event.probe_hello", mapOf("pid" to Process.myPid(), "version" to TAG))
                udsClient?.incomingMessages?.collect { jsonLine: String ->
                    try {
                        ConfigManager.updateConfig(jsonLine)
                    } catch (e: Exception) {
                        logError("Error processing config message: $e")
                    }
                }
                logError("UDS message stream ended. Restarting cycle.")
                udsClient?.stop()
            } catch (e: Exception) {
                logError("Exception in communication cycle: $e. Restarting.")
            }
            delay(5000L)
        }
    }

    private fun sendEventToDaemon(type: String, payload: Any) {
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
        @Volatile private var frozenUids = emptySet<Int>()
        private val jsonParser = JsonParser()

        fun updateConfig(jsonString: String) {
            try {
                val root = jsonParser.parse(jsonString).asJsonObject
                if (root.has("payload") && root["payload"].isJsonObject) {
                    val payload = root.getAsJsonObject("payload")
                    if (payload.has("frozen_uids") && payload["frozen_uids"].isJsonArray) {
                        val uids = payload.getAsJsonArray("frozen_uids").map { it.asInt }.toSet()
                        frozenUids = uids
                        XposedBridge.log("$TAG: Config updated. Now tracking ${uids.size} frozen uids.")
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("$TAG: [ERROR] Failed to parse probe config: $e. JSON: $jsonString")
            }
        }

        fun isUidFrozen(uid: Int): Boolean {
            return frozenUids.contains(uid)
        }
    }

    private fun log(message: String) = XposedBridge.log("[$TAG] $message")
    private fun logError(message: String) = XposedBridge.log("[$TAG] [ERROR] $message")
}