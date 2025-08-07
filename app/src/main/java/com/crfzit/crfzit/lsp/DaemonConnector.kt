// app/src/main/java/com/crfzit/crfzit/lsp/DaemonConnector.kt
package com.crfzit.crfzit.lsp

import de.robv.android.xposed.XposedBridge
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [核心重构] 为 ProbeHook 设计的持久化、自动重连的TCP连接器。
 * 它取代了原来每次事件都创建新Socket的低效模型。
 */
class DaemonConnector(
    private val host: String,
    private val port: Int,
    private val tag: String,
    // [修正] PID通过构造函数传入，解除对Android框架的直接依赖
    private val pid: Int
) {
    // 使用单线程执行器来保证所有网络操作（连接、发送）的顺序性
    private val executor = Executors.newSingleThreadExecutor()
    // 消息队列，用于缓存待发送的消息
    private val messageQueue = LinkedBlockingQueue<String>()
    // 运行状态标志
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var outputStream: OutputStream? = null

    companion object {
        private const val RECONNECT_DELAY_MS = 5000L
        private const val READ_TIMEOUT_MS = 30_000 // 30秒读超时
    }

    /**
     * 启动连接器。这将启动一个后台任务，负责连接、重连和发送消息。
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            executor.submit { connectionLoop() }
            log("DaemonConnector started.")
        }
    }

    /**
     * 停止连接器。
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            // 中断线程来唤醒可能的等待
            executor.shutdownNow()
            log("DaemonConnector stopped.")
        }
    }

    /**
     * 发送消息。这是一个非阻塞方法，它将消息放入队列，由后台任务发送。
     */
    fun sendMessage(jsonMessage: String) {
        if (!isRunning.get()) return
        // offer() 不会阻塞，如果队列满了会返回false，但LinkedBlockingQueue默认是无界的
        messageQueue.offer(jsonMessage)
    }

    private fun log(message: String) = XposedBridge.log("[$tag/Connector] $message")
    private fun logError(message: String) = XposedBridge.log("[$tag/Connector] [ERROR] $message")

    /**
     * 主循环，运行在后台线程中。
     */
    private fun connectionLoop() {
        while (isRunning.get()) {
            try {
                // 1. 尝试连接
                log("Attempting to connect to daemon...")
                val newSocket = Socket(host, port)
                newSocket.soTimeout = READ_TIMEOUT_MS // 设置读超时

                // 连接成功
                socket = newSocket
                outputStream = newSocket.outputStream
                log("Successfully connected to daemon.")

                // 发送一个 "hello" 消息，让守护进程知道我们是Probe
                // [修正] 使用构造函数传入的pid
                sendMessage("{\"type\":\"event.probe_hello\",\"payload\":{\"pid\":$pid,\"version\":\"$tag\"}}")

                // 2. 进入消息发送/读取循环
                communicationLoop()

            } catch (e: InterruptedException) {
                // 线程被中断，意味着要关闭了
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                // 任何连接或通信错误
                when (e) {
                    is SocketTimeoutException -> logError("Connection timed out. Reconnecting...")
                    is IOException -> logError("Connection failed or lost: ${e.message}. Retrying...")
                    else -> logError("Unhandled exception in connection loop: $e")
                }
            } finally {
                // 3. 清理并等待重连
                cleanupSocket()
                if (isRunning.get()) {
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        log("Connection loop terminated.")
        cleanupSocket() // 确保退出时也清理
    }

    /**
     * 通信循环，负责从队列中取出消息发送，并处理守护进程的响应。
     */
    private fun communicationLoop() {
        val currentSocket = this.socket ?: return
        val currentOutputStream = this.outputStream ?: return
        val reader = currentSocket.getInputStream().bufferedReader(StandardCharsets.UTF_8)

        while (isRunning.get() && currentSocket.isConnected && !currentSocket.isClosed) {
            try {
                // 使用 poll() 和超时来避免永久阻塞，同时能响应守护进程的消息
                val messageToSend = messageQueue.poll(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)

                if (messageToSend != null) {
                    // 发送排队的消息
                    currentOutputStream.write((messageToSend + "\n").toByteArray(StandardCharsets.UTF_8))
                    currentOutputStream.flush()
                } else {
                    // 如果超时了还没等到消息，说明连接可能已经断了（守护进程没发心跳）
                    // 这是一个隐式的心跳检查
                    throw SocketTimeoutException("No message received or sent within timeout period.")
                }

                // 检查是否有来自守护进程的消息（例如，配置更新）
                if (reader.ready()) {
                    val response = reader.readLine()
                    if (response != null && response.contains("probe_config_update")) {
                        // [修正] 直接调用顶级的 ConfigManager
                        ConfigManager.updateConfig(response)
                    }
                }

            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e // 抛出让外层循环处理
            } catch (e: IOException) {
                logError("Communication error: ${e.message}")
                throw e // 抛出让外层循环处理
            }
        }
    }


    private fun cleanupSocket() {
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outputStream = null
    }
}