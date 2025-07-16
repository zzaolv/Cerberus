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
            if (durationMs > 0 && lastTotalRxBytes > 0) {
                val deltaRx = currentRxBytes - lastTotalRxBytes
                val deltaTx = currentTxBytes - lastTotalTxBytes

                val downSpeed = if (deltaRx >= 0) (deltaRx * 1000 / durationMs) * 8 else 0
                val upSpeed = if (deltaTx >= 0) (deltaTx * 1000 / durationMs) * 8 else 0

                emit(NetworkSpeed(downloadSpeedBps = downSpeed, uploadSpeedBps = upSpeed))
            } else {
                 emit(NetworkSpeed(0, 0))
            }


            lastTotalRxBytes = currentRxBytes
            lastTotalTxBytes = currentTxBytes
            lastTimestamp = currentTimestamp

            delay(1000)
        }
    }
}