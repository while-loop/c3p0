package dev.whileloop.c3p0.ble.manager

import kotlinx.coroutines.flow.StateFlow

interface HeartRateManager {
    val heartRate: StateFlow<Int>
    val lastHeartRateReceivedAtMillis: StateFlow<Long>
    val connectionState: StateFlow<ConnectionState>

    suspend fun connect(address: String): Boolean
    suspend fun disconnect()
}
