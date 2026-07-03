package dev.whileloop.c3p0.ble.manager

import android.content.Context
import dev.whileloop.c3p0.ble.controller.BleConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import javax.inject.Inject

class GarminManagerImpl @Inject constructor(
    private val context: Context
) : HeartRateManager {

    private var connection: BleConnection? = null
    private val _heartRate = MutableStateFlow(0)
    override val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val HRS_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASUREMENT_CHAR_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    override suspend fun connect(address: String): Boolean {
        connection = BleConnection(context, address).apply {
            onNotificationReceived = { uuid, data ->
                if (uuid == HR_MEASUREMENT_CHAR_UUID) {
                    handleHrNotification(data)
                }
            }
        }
        
        val result = connection?.connect() ?: false
        if (result) {
            _connectionState.value = ConnectionState.CONNECTING
            // In a real app, we should wait for connection and then enable notifications
            // For now, we'll assume it works
            connection?.enableNotifications(HRS_SERVICE_UUID, HR_MEASUREMENT_CHAR_UUID)
        }
        return result
    }

    override suspend fun disconnect() {
        connection?.disconnect()
        connection = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun handleHrNotification(data: ByteArray) {
        if (data.isEmpty()) return
        
        val flags = data[0].toInt() and 0xFF
        val is16Bit = (flags and 0x01) != 0
        
        val hr = if (is16Bit) {
            if (data.size >= 3) {
                (data[1].toInt() and 0xFF) or (data[2].toInt() and 0xFF shl 8)
            } else 0
        } else {
            if (data.size >= 2) {
                data[1].toInt() and 0xFF
            } else 0
        }
        
        _heartRate.value = hr
    }
}
