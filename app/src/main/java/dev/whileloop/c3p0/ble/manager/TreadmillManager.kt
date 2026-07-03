package dev.whileloop.c3p0.ble.manager

import dev.whileloop.c3p0.ble.model.TreadmillMode
import dev.whileloop.c3p0.ble.model.TreadmillStatus
import dev.whileloop.c3p0.data.model.UnitSystem
import kotlinx.coroutines.flow.StateFlow

interface TreadmillManager {
    val status: StateFlow<TreadmillStatus>
    val connectionState: StateFlow<ConnectionState>

    suspend fun connect(address: String): Boolean
    suspend fun disconnect()
    suspend fun start(): Boolean
    suspend fun stop(): Boolean
    suspend fun setSpeed(speed: Float): Boolean // speed in km/h (e.g., 3.0)
    suspend fun setMode(mode: TreadmillMode): Boolean
    suspend fun setUnitSystem(unitSystem: UnitSystem): Boolean
}

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING
}
