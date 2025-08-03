// app/src/main/java/com/crfzit/crfzit/data/system/NetworkMonitor.kt
package com.crfzit.crfzit.data.system

import android.net.TrafficStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class NetworkSpeed(
    val downloadSpeedBps: Long = 0,
    val uploadSpeedBps: Long = 0
)

class NetworkMonitor {

    private var lastTotalRxBytes: Long = 0
    private var lastTotalTxBytes: Long = 0
    private var lastTimestamp: Long = 0

    init {
        // 初始化时获取一次基准值
        lastTotalRxBytes = TrafficStats.getTotalRxBytes()
        lastTotalTxBytes = TrafficStats.getTotalTxBytes()
        lastTimestamp = System.currentTimeMillis()
    }

    fun getSpeedStream(): Flow<NetworkSpeed> = flow {
        while (true) {
            val currentRxBytes = TrafficStats.getTotalRxBytes()
            val currentTxBytes = TrafficStats.getTotalTxBytes()
            val currentTimestamp = System.currentTimeMillis()

            val durationMs = currentTimestamp - lastTimestamp
            // 确保持续时间大于0，且初始值有效
            if (durationMs > 0 && lastTotalRxBytes != TrafficStats.UNSUPPORTED.toLong()) {
                val deltaRx = currentRxBytes - lastTotalRxBytes
                val deltaTx = currentTxBytes - lastTotalTxBytes

                // 每秒比特数 = (字节差 * 1000 / 毫秒差) * 8
                val downSpeed = if (deltaRx >= 0) (deltaRx * 1000 / durationMs) * 8 else 0
                val upSpeed = if (deltaTx >= 0) (deltaTx * 1000 / durationMs) * 8 else 0

                emit(NetworkSpeed(downloadSpeedBps = downSpeed, uploadSpeedBps = upSpeed))
            } else {
                 emit(NetworkSpeed(0, 0))
            }

            // 更新基准值
            lastTotalRxBytes = currentRxBytes
            lastTotalTxBytes = currentTxBytes
            lastTimestamp = currentTimestamp

            // [FIX] 刷新间隔改为5秒
            delay(5000)
        }
    }
}