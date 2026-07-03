package dev.whileloop.c3p0.ble.manager.mock

import dev.whileloop.c3p0.ble.manager.ConnectionState
import dev.whileloop.c3p0.ble.manager.HeartRateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import kotlin.random.Random

class MockHeartRateManager @Inject constructor() : HeartRateManager {

    private val _heartRate = MutableStateFlow(0)
    override val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

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
    
    suspend fun simulateHeartRate(baseHr: Int = 70) {
        _heartRate.value = baseHr
        while (true) {
            val delta = Random.nextInt(-2, 3)
            _heartRate.value = (_heartRate.value + delta).coerceIn(60, 180)
            delay(1000)
        }
    }
}
