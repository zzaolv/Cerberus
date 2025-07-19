// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import android.os.Process
import com.crfzit.crfzit.data.model.AppInstanceKey
import com.crfzit.crfzit.data.model.CerberusMessage
import com.crfzit.crfzit.data.model.ProbeConfigUpdatePayload
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
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

class ProbeHook : IXposedHookLoadPackage {

    private val probeScope = CoroutineScope(SupervisorJob() + Executors.newCachedThreadPool().asCoroutineDispatcher())
    private val udsClient = UdsClient(probeScope)
    private val gson = Gson()
    private val frozenAppsCache = MutableStateFlow<Set<AppInstanceKey>>(emptySet())
    private val pendingThawRequests = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

    companion object {
        private const val TAG = "CerberusProbe_v1.1_Fixed"
        private const val PER_USER_RANGE = 100000 // Android多用户的UID范围
        private const val THAW_TIMEOUT_MS = 1500L // 解冻请求超时时间
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        log("Loading into android package (system_server), PID: ${Process.myPid()}. Attaching hooks...")
        initializeHooks(lpparam.classLoader)
    }

    private fun initializeHooks(classLoader: ClassLoader) {
        setupUdsCommunication()
        hookActivityStarter(classLoader) // 关键的预先解冻Hook
        hookActivityManagerExtras(classLoader)
        hookPowerManager(classLoader)
        hookAlarmManager(classLoader)
        log("All hooks attached. Probe is active.")
    }

    // [核心修复] 这是解决黑白屏问题的关键所在
    private fun hookActivityStarter(classLoader: ClassLoader) {
        try {
            val activityStarterClass = XposedHelpers.findClass("com.android.server.wm.ActivityStarter", classLoader)
            XposedBridge.hookAllMethods(activityStarterClass, "execute", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val request = XposedHelpers.getObjectField(param.thisObject, "mRequest") ?: return
                    val intent = XposedHelpers.getObjectField(request, "intent") as? android.content.Intent ?: return
                    val callingUid = XposedHelpers.getIntField(request, "callingUid")

                    // 忽略系统UID的启动请求
                    if (callingUid < Process.FIRST_APPLICATION_UID) return

                    val component = intent.component ?: return
                    val packageName = component.packageName

                    // [核心修复] 使用UID和常量进行数学运算，100%可靠地获取userId
                    val userId = callingUid / PER_USER_RANGE

                    // 检查应用是否在我们的冻结缓存中
                    if (isAppFrozen(packageName, userId)) {
                        log("Intercepted launch of frozen app: $packageName (user: $userId). Requesting pre-emptive thaw...")

                        // [核心修复] 同步请求解冻并等待结果
                        val success = requestThawAndWait(packageName, userId)

                        if (!success) {
                            logError("Thaw FAILED for $packageName. Aborting launch to prevent black screen.")
                            // 中止Activity启动，防止黑屏/白屏
                            param.result = XposedHelpers.getStaticIntField(ActivityManager::class.java, "START_ABORTED")
                        } else {
                            log("Thaw successful for $packageName. Proceeding with launch.")
                            // 解冻成功，继续正常启动流程
                        }
                    }
                }
            })
            log("Hooked ActivityStarter#execute for pre-emptive thaw.")
        } catch (t: Throwable) {
            logError("CRITICAL: Failed to hook ActivityStarter: $t")
        }
    }

    // [核心修复] 实现同步请求解冻的逻辑
    private fun requestThawAndWait(packageName: String, userId: Int): Boolean {
        val reqId = UUID.randomUUID().toString()
        val future = CompletableFuture<Boolean>()
        try {
            pendingThawRequests[reqId] = future
            val payload = AppInstanceKey(packageName, userId)
            // 发送高优先级解冻请求到守护进程
            sendToDaemon("cmd.request_immediate_unfreeze", payload, reqId)
            // 阻塞并等待守护进程的响应，有超时保护
            return future.get(THAW_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            logError("Exception in requestThawAndWait for $packageName: ${e.message}")
            return false
        } finally {
            pendingThawRequests.remove(reqId)
        }
    }

    private fun setupUdsCommunication() {
        udsClient.start()
        probeScope.launch(Dispatchers.IO) {
            delay(5000) // 等待daemon启动
            sendToDaemon("event.probe_hello", mapOf("version" to "10.1", "pid" to Process.myPid()))

            udsClient.incomingMessages.collectLatest { jsonLine ->
                try {
                    val baseMsg = gson.fromJson(jsonLine, BaseMessage::class.java)
                    // 处理来自daemon的响应
                    if (baseMsg.type.startsWith("resp.")) {
                        pendingThawRequests.remove(baseMsg.requestId)?.complete(baseMsg.type == "resp.unfreeze_complete")
                    }
                    // 处理来自daemon的冻结列表更新
                    else if (baseMsg.type == "stream.probe_config_update") {
                        val type = object : TypeToken<CerberusMessage<ProbeConfigUpdatePayload>>() {}.type
                        val msg = gson.fromJson<CerberusMessage<ProbeConfigUpdatePayload>>(jsonLine, type)
                        val newCache = msg.payload.frozenApps.toSet()
                        if (frozenAppsCache.value != newCache) {
                            frozenAppsCache.value = newCache
                            log("Updated frozen apps cache. Size: ${newCache.size}")
                        }
                    }
                } catch (e: Exception) { logError("Error processing message from daemon: $e") }
            }
        }
    }

    private fun isAppFrozen(packageName: String?, userId: Int): Boolean {
        if (packageName == null) return false
        val key = AppInstanceKey(packageName, userId)
        return frozenAppsCache.value.contains(key)
    }

    private fun sendToDaemon(type: String, payload: Any, reqId: String? = null) {
        probeScope.launch {
            try {
                udsClient.sendMessage(gson.toJson(CerberusMessage(10, type, reqId, payload)))
            } catch (e: Exception) {
                logError("Daemon send error: $e")
            }
        }
    }

    private data class BaseMessage(val type: String, @SerializedName("req_id") val requestId: String?)
    private fun log(message: String) = XposedBridge.log("$TAG: $message")
    private fun logError(message: String) = XposedBridge.log("$TAG: [ERROR] $message")

    // ... 其他辅助性Hook保持不变 ...
    private fun hookActivityManagerExtras(classLoader: ClassLoader) {
        // (此部分代码与您提供的版本一致，此处省略以保持简洁)
        try {
            val broadcastQueueClass = XposedHelpers.findClass("com.android.server.am.BroadcastQueue", classLoader)
            XposedBridge.hookAllMethods(broadcastQueueClass, "processNextBroadcast", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val queue = param.thisObject
                    val broadcastsField = XposedHelpers.getObjectField(queue, "mBroadcasts") as? ArrayList<*> ?: return
                    if (broadcastsField.isEmpty()) return
                    val br = broadcastsField[0] ?: return
                    val receivers = XposedHelpers.getObjectField(br, "receivers") as? List<*> ?: return
                    val firstReceiver = receivers.firstOrNull() ?: return
                    val targetApp = XposedHelpers.getObjectField(firstReceiver, "app")
                    val appInfo = targetApp?.let { XposedHelpers.getObjectField(it, "info") as? ApplicationInfo } ?: return
                    if (isAppFrozen(appInfo.packageName, appInfo.uid / PER_USER_RANGE)) {
                        log("Broadcast Interception: Skipping for frozen app ${appInfo.packageName}")
                        val broadcastSuccessCode = XposedHelpers.getStaticIntField(ActivityManager::class.java, "BROADCAST_SUCCESS")
                        XposedHelpers.callMethod(queue, "finishReceiverLocked", br, broadcastSuccessCode, null, null, false, true)
                        XposedHelpers.callMethod(queue, "scheduleBroadcastsLocked")
                        param.result = null
                    }
                }
            })
            log("Hooked BroadcastQueue.")
        } catch (t: Throwable) {
            logError("Failed to hook BroadcastQueue: $t")
        }
        try {
            val appErrorsClass = XposedHelpers.findClass("com.android.server.am.AppErrors", classLoader)
            XposedBridge.hookAllMethods(appErrorsClass, "handleShowAnrUi", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val processRecord = param.args.firstOrNull { it?.javaClass?.name?.contains("ProcessRecord") == true } ?: return
                    val appInfo = XposedHelpers.getObjectField(processRecord, "info") as? ApplicationInfo ?: return
                    if (isAppFrozen(appInfo.packageName, appInfo.uid / PER_USER_RANGE)) {
                        log("ANR Intervention: Suppressing for frozen app ${appInfo.packageName}")
                        param.result = null
                    }
                }
            })
            log("Hooked AppErrors.")
        } catch (t: Throwable) {
            logError("Failed to hook AppErrors: $t")
        }
    }
    private fun hookPowerManager(classLoader: ClassLoader) {
        // (此部分代码与您提供的版本一致，此处省略以保持简洁)
        try {
            val pmsClass = XposedHelpers.findClass("com.android.server.power.PowerManagerService", classLoader)
            XposedBridge.hookAllMethods(pmsClass, "acquireWakeLock", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val callingUid = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.os.Binder", null), "getCallingUid") as Int
                    val pkg = param.args.firstOrNull { it is String && it.contains('.') } as? String
                    if (isAppFrozen(pkg, callingUid / PER_USER_RANGE)) {
                        log("WakeLock Interception: Denying for frozen app $pkg")
                        param.result = null
                    }
                }
            })
            log("PowerManager hooks attached successfully.")
        } catch (t: Throwable) {
            logError("Failed to attach PowerManager hooks: $t")
        }
    }
    private fun hookAlarmManager(classLoader: ClassLoader) {
        // (此部分代码与您提供的版本一致，此处省略以保持简洁)
        try {
            val alarmManagerClassName = try {
                XposedHelpers.findClass("com.android.server.alarm.AlarmManagerService", classLoader)
                "com.android.server.alarm.AlarmManagerService"
            } catch (e: XposedHelpers.ClassNotFoundError) {
                "com.android.server.AlarmManagerService"
            }
            val amsClass = XposedHelpers.findClass(alarmManagerClassName, classLoader)
            XposedBridge.hookAllMethods(amsClass, "set", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pendingIntent = param.args.firstOrNull { it is android.app.PendingIntent } as? android.app.PendingIntent ?: return
                    val pkg = pendingIntent.creatorPackage
                    val uid = pendingIntent.creatorUid
                    if (isAppFrozen(pkg, uid / PER_USER_RANGE)) {
                        log("Alarm Interception: Denying for frozen app $pkg")
                        param.result = null
                    }
                }
            })
            log("AlarmManager hooks attached successfully.")
        } catch (t: Throwable) {
            logError("Failed to attach AlarmManager hooks: $t")
        }
    }
}