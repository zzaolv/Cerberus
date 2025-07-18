// app/src/main/java/com/crfzit/crfzit/data/uds/UdsClient.kt
package com.crfzit.crfzit.data.uds

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class UdsClient(private val scope: CoroutineScope) {

    private var socket: LocalSocket? = null
    private var outputStream: OutputStream? = null // 【新增】
    private var connectionJob: Job? = null
    private val _incomingMessages = MutableSharedFlow<String>(replay = 1)
    val incomingMessages = _incomingMessages.asSharedFlow()

    companion object {
        private const val TAG = "CerberusUdsClient"
        private const val SOCKET_NAME = "cerberus_socket"
        private const val RECONNECT_DELAY_MS = 5000L
    }

    fun start() {
        if (connectionJob?.isActive == true) {
            Log.d(TAG, "UDS client is already running.")
            return
        }
        Log.i(TAG, "UDS client start() called.")
        connectionJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    Log.i(TAG, "Attempting to connect to UDS: @$SOCKET_NAME...")
                    socket = LocalSocket(LocalSocket.SOCKET_STREAM).also {
                        it.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                        // 【新增】连接成功后获取输出流
                        outputStream = it.outputStream
                    }
                    Log.i(TAG, "Successfully connected to daemon.")
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
    
    // 【新增】发送消息的方法
    fun sendMessage(message: String) {
        // 在IO线程中执行发送操作，避免阻塞调用者
        scope.launch(Dispatchers.IO) {
            val stream = outputStream
            if (stream == null || socket?.isConnected != true) {
                Log.w(TAG, "Cannot send message, socket is not connected.")
                return@launch
            }
            try {
                // JSON Lines 协议，每条消息后加换行符
                stream.write((message + "\n").toByteArray(StandardCharsets.UTF_8))
                stream.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send message: ${e.message}")
                // 发送失败通常意味着连接已断开，触发重连
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
                        _incomingMessages.emit(line)
                    }
                }
            }
        } catch (e: Exception) {
            if (scope.isActive) {
                Log.e(TAG, "Error while reading from socket: ${e.message}")
            }
        } finally {
            Log.i(TAG, "Socket read loop finished or connection lost.")
            cleanupSocket()
        }
    }

    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        cleanupSocket()
        Log.i(TAG, "UDS client stopped.")
    }

    private fun cleanupSocket() {
        try {
            outputStream?.close()
        } catch (e: IOException) { /* ignore */ }
        try {
            socket?.close()
        } catch (e: IOException) { /* ignore */ }
        socket = null
        outputStream = null
    }
}