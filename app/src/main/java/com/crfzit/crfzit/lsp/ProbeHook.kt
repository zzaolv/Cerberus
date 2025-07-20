// app/src/main/java/com/crfzit/crfzit/lsp/ProbeHook.kt
package com.crfzit.crfzit.lsp

import android.os.Process
import com.crfzit.crfzit.data.model.CerberusMessage
import com.crfzit.crfzit.data.uds.UdsClient
import com.google.gson.Gson
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*
import java.util.concurrent.Executors

@OptIn(DelicateCoroutinesApi::class)
class ProbeHook : IXposedHookLoadPackage {

    private val scope = GlobalScope
    private var udsClient: UdsClient? = null
    private val gson = Gson()

    companion object {
        private const val TAG = "CerberusProbe_v23.0_Heartbeat"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") {
            log("Loading into system_server (PID: ${Process.myPid()}).")

            val singleThreadContext = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

            udsClient = UdsClient(scope)

            scope.launch(singleThreadContext) {
                // 启动一个永不停止的心跳循环
                runHeartbeatLoop()
            }
        }
    }

    private suspend fun runHeartbeatLoop() {
        log("Heartbeat loop started.")

        // 先启动UDS客户端
        udsClient?.start()

        while (scope.isActive) {
            try {
                log("Sending hello heartbeat to daemon...")
                sendToDaemon("event.probe_hello", mapOf("pid" to Process.myPid(), "version" to TAG))
            } catch (e: Exception) {
                logError("Error sending heartbeat: $e")
            }
            // 每5秒发送一次
            delay(5000L)
        }
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

    private fun log(message: String) = XposedBridge.log("[$TAG] $message")
    private fun logError(message: String) = XposedBridge.log("[$TAG] [ERROR] $message")
}