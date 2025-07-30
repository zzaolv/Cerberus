// app/src/main/java/com/crfzit/crfzit/data/uds/TcpClient.kt
package com.crfzit.crfzit.data.uds

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

class TcpClient(private val scope: CoroutineScope) {

    private enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var outputStream: OutputStream? = null

    private var connectionJob: Job? = null

    // [修改] 将 incomingMessages 的 replay 缓存增加，以防UI重建时丢失少量数据
    private val _incomingMessages = MutableSharedFlow<String>(replay = 20, extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()

    // [新增] 暴露连接状态给外部
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    companion object {
        private const val TAG = "CerberusTcpClient"
        private const val HOST = "127.0.0.1"
        private const val PORT = 28900
        private const val RECONNECT_DELAY_MS = 3000L
        private const val READ_TIMEOUT_MS = 30_000 // 30秒读超时
    }

    fun start() {
        if (connectionJob?.isActive == true) {
            Log.d(TAG, "TCP client is already running.")
            return
        }
        Log.i(TAG, "TCP client start() called.")

        connectionJob = scope.launch(Dispatchers.IO) {
            // [核心重构] 使用无限循环来维持连接
            while (isActive) {
                var currentState = ConnectionState.DISCONNECTED
                try {
                    // 状态: DISCONNECTED -> CONNECTING
                    currentState = ConnectionState.CONNECTING
                    _isConnected.value = false
                    Log.i(TAG, "Attempting to connect to TCP server: $HOST:$PORT...")
                    
                    val newSocket = Socket(HOST, PORT)
                    newSocket.soTimeout = READ_TIMEOUT_MS // 设置读超时

                    socket = newSocket
                    outputStream = newSocket.outputStream

                    // 状态: CONNECTING -> CONNECTED
                    currentState = ConnectionState.CONNECTED
                    _isConnected.value = true
                    Log.i(TAG, "Successfully connected to daemon via TCP.")

                    // [新增] 发送一个 "hello" 消息，让后端识别我们是UI客户端
                    sendMessage("{\"type\":\"hello.ui\"}")

                    listenForMessages()

                } catch (e: Exception) {
                    when (e) {
                        is SocketTimeoutException -> {
                            Log.w(TAG, "Connection timed out (no heartbeat from daemon). Reconnecting...")
                        }
                        is IOException -> {
                            Log.w(TAG, "Connection failed or lost: ${e.message}. Retrying in ${RECONNECT_DELAY_MS}ms...")
                        }
                        else -> {
                            Log.e(TAG, "Unhandled exception in connection loop: ${e.message}", e)
                        }
                    }
                } finally {
                    // 确保无论如何都清理资源并进入重试等待
                    cleanupSocket()
                    _isConnected.value = false
                    if (isActive) {
                        delay(RECONNECT_DELAY_MS)
                    }
                }
            }
        }
    }
    
    fun sendMessage(message: String) {
        // sendMessage 逻辑保持不变，但现在更健壮，因为连接层会自动恢复
        scope.launch(Dispatchers.IO) {
            val stream = outputStream
            if (stream == null || socket?.isConnected != true) {
                Log.w(TAG, "Cannot send message, socket is not connected.")
                return@launch
            }
            try {
                // 加锁以防止多线程写竞争，虽然当前场景不明显，但这是个好习惯
                synchronized(this) {
                    stream.write((message + "\n").toByteArray(StandardCharsets.UTF_8))
                    stream.flush()
                }
                Log.d(TAG, "Sent: $message")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send message: ${e.message}")
                // 触发连接断开
                cleanupSocket()
            }
        }
    }

    private suspend fun listenForMessages() {
        // 这个函数现在只负责在已连接的状态下读取数据
        val currentSocket = socket ?: return
        Log.d(TAG, "Starting to listen for messages...")
        currentSocket.getInputStream().bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (currentSocket.isConnected && scope.isActive) {
                // readLine() 会遵守 soTimeout 设置
                val line = reader.readLine() ?: break // 如果流关闭，readLine返回null
                if (line.isNotBlank()) {
                     Log.d(TAG, "Rcvd: $line")
                    _incomingMessages.emit(line)
                }
            }
        }
    }

    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        cleanupSocket()
        Log.i(TAG, "TCP client stopped.")
    }

    private fun cleanupSocket() {
        try { outputStream?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        outputStream = null
    }
}