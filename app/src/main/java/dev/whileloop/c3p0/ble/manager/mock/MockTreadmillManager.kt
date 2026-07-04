package dev.whileloop.c3p0.ble.manager.mock

import dev.whileloop.c3p0.ble.manager.ConnectionState
import dev.whileloop.c3p0.ble.manager.TreadmillManager
import dev.whileloop.c3p0.ble.model.TreadmillMode
import dev.whileloop.c3p0.ble.model.TreadmillState
import dev.whileloop.c3p0.ble.model.TreadmillStatus
import dev.whileloop.c3p0.data.model.UnitSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

class MockTreadmillManager @Inject constructor() : TreadmillManager {

    private val _status = MutableStateFlow(TreadmillStatus())
    override val status: StateFlow<TreadmillStatus> = _status.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override suspend fun connect(address: String): Boolean {
        _connectionState.value = ConnectionState.CONNECTING
        delay(1000)
        _connectionState.value = ConnectionState.CONNECTED
        return true
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTING
        delay(500)
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun start(): Boolean {
        _status.value = _status.value.copy(state = TreadmillState.ACTIVE)
        return true
    }

    override suspend fun stop(): Boolean {
        _status.value = _status.value.copy(state = TreadmillState.STOPPED, speed = 0f)
        return true
    }

    override suspend fun setSpeed(speed: Float): Boolean {
        _status.value = _status.value.copy(speed = speed.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH))
        return true
    }

    override suspend fun setMode(mode: TreadmillMode): Boolean {
        _status.value = _status.value.copy(mode = mode)
        return true
    }

    override suspend fun setUnitSystem(unitSystem: UnitSystem): Boolean {
        _status.value = _status.value.copy(unitSystem = unitSystem)
        return true
    }
    
    // Helper to simulate walking
    suspend fun simulateWalking() {
        while (true) {
            val s = _status.value
            if (s.state == TreadmillState.ACTIVE && s.speed > 0) {
                _status.value = s.copy(
                    time = s.time + 1,
                    distance = s.distance + (s.speed / 3.6).toInt(), // very rough
                    steps = s.steps + (s.speed * 1.5).toInt()
                )
            }
            delay(1000)
        }
    }

    companion object {
        private const val MIN_SPEED_KMH = 0f
        private const val MAX_SPEED_KMH = 6f
    }
}
