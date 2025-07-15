package com.crfzit.crfzit.data.uds

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.IOException
import java.nio.charset.StandardCharsets

class UdsClient(private val scope: CoroutineScope) {

    private var socket: LocalSocket? = null
    private var connectionJob: Job? = null
    private val _incomingMessages = MutableSharedFlow<String>(replay = 1)
    val incomingMessages = _incomingMessages.asSharedFlow()

    companion object {
        private const val TAG = "CerberusUdsClient"
        // 与 daemon 和 SELinux 策略中的 socket name 保持一致
        private const val SOCKET_NAME = "cerberus_socket"
        private const val RECONNECT_DELAY_MS = 5000L
    }

    fun start() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    Log.i(TAG, "Attempting to connect to UDS: @$SOCKET_NAME...")
                    socket = LocalSocket().also {
                        // 使用 ABSTRACT namespace，这与 C++ daemon 的实现匹配
                        it.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                    }
                    Log.i(TAG, "Successfully connected to daemon.")
                    
                    // 发送一个心跳或版本信息，表明UI已连接
                    // sendMessage("{\"v\": 1, \"type\": \"cmd.client_hello\"}")
                    
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

    private suspend fun listenForMessages() {
        val currentSocket = socket ?: return
        try {
            // 使用 use 块确保资源自动关闭
            currentSocket.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                while (currentSocket.isConnected && scope.isActive) {
                    val line = reader.readLine() ?: break // 读取到流末尾，连接已断开
                    if (line.isNotBlank()) {
                        // Log.v(TAG, "Received line: $line") // Verbose log for debugging
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
            socket?.close()
        } catch (e: IOException) {
            // Ignore
        }
        socket = null
    }
}