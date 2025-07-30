// app/src/main/java/com/crfzit/crfzit/data/uds/UdsClient.kt
package com.crfzit.crfzit.data.uds

// [核心修改] 移除LocalSocket，引入标准Java Socket
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.IOException
import java.io.OutputStream
import java.net.Socket // 引入 TCP Socket
import java.nio.charset.StandardCharsets

class UdsClient(private val scope: CoroutineScope) {

    // [核心修改] socket 类型变为 java.net.Socket
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var connectionJob: Job? = null
    private val _incomingMessages = MutableSharedFlow<String>(replay = 10, extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()

    companion object {
        private const val TAG = "CerberusTcpClient" // 重命名日志标签
        private const val HOST = "127.0.0.1"
        private const val PORT = 28900
        private const val RECONNECT_DELAY_MS = 3000L
    }

    fun start() {
        if (connectionJob?.isActive == true) {
            Log.d(TAG, "TCP client is already running.")
            return
        }
        Log.i(TAG, "TCP client start() called.")
        connectionJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    // [核心修改] 连接日志显示IP和端口
                    Log.i(TAG, "Attempting to connect to TCP server: $HOST:$PORT...")
                    // [核心修改] 创建并连接标准 TCP Socket
                    socket = Socket(HOST, PORT).also {
                        outputStream = it.outputStream
                    }
                    Log.i(TAG, "Successfully connected to daemon via TCP.")
                    listenForMessages()
                } catch (e: IOException) {
                    Log.w(TAG, "Connection failed or lost: ${e.message}. Retrying in ${RECONNECT_DELAY_MS}ms...")
                    cleanupSocket()
                }
                if (isActive) {
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
    }

    // sendMessage 和 listenForMessages 逻辑完全保持不变
    fun sendMessage(message: String) {
        scope.launch(Dispatchers.IO) {
            val stream = outputStream
            if (stream == null || socket?.isConnected != true) {
                Log.w(TAG, "Cannot send message, socket is not connected.")
                return@launch
            }
            try {
                stream.write((message + "\n").toByteArray(StandardCharsets.UTF_8))
                stream.flush()
                Log.d(TAG, "Sent: $message")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send message: ${e.message}")
                cleanupSocket()
            }
        }
    }

    private suspend fun listenForMessages() {
        val currentSocket = socket ?: return
        try {
            currentSocket.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                while (currentSocket.isConnected && scope.isActive) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        Log.d(TAG, "Rcvd: $line")
                        _incomingMessages.emit(line)
                    }
                }
            }
        } catch (e: Exception) {
            if (scope.isActive) Log.e(TAG, "Error while reading from socket: ${e.message}")
        } finally {
            Log.i(TAG, "Socket read loop finished or connection lost.")
            cleanupSocket()
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