package dev.whileloop.c3p0.ble.manager

import android.content.Context
import dev.whileloop.c3p0.ble.controller.BleConnection
import dev.whileloop.c3p0.ble.model.*
import dev.whileloop.c3p0.data.model.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.*
import javax.inject.Inject

class WalkingPadManagerImpl @Inject constructor(
    private val context: Context
) : TreadmillManager {

    private var connection: BleConnection? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectionStateJob: Job? = null
    private val _status = MutableStateFlow(TreadmillStatus())
    override val status: StateFlow<TreadmillStatus> = _status.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private var desiredUnitSystem: UnitSystem = UnitSystem.Metric
    private var isSyncingUnitSystem = false

    private val SERVICE_UUID = UUID.fromString("0000fe00-0000-1000-8000-00805f9b34fb")
    private val NOTIFY_CHAR_UUID = UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb")
    private val WRITE_CHAR_UUID = UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb")

    override suspend fun connect(address: String): Boolean {
        connection = BleConnection(context, address).apply {
            onNotificationReceived = { uuid, data ->
                if (uuid == NOTIFY_CHAR_UUID) {
                    handleNotification(data)
                }
            }
            onServicesDiscovered = {
                enableNotifications(SERVICE_UUID, NOTIFY_CHAR_UUID)
            }
        }
        
        // In a real app, we'd wait for connection state to be CONNECTED
        // For simplicity in this impl, we just call connect and return true
        val result = connection?.connect() ?: false
        if (result) {
            _connectionState.value = ConnectionState.CONNECTING
            connection?.let { bleConnection ->
                connectionStateJob?.cancel()
                connectionStateJob = scope.launch {
                    bleConnection.connectionState.collect { state ->
                        _connectionState.value = state
                    }
                }
            }
        }
        return result
    }

    override suspend fun disconnect() {
        connectionStateJob?.cancel()
        connectionStateJob = null
        connection?.disconnect()
        connection = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun start(): Boolean {
        return sendCommand(byteArrayOf(0xF7.toByte(), 0xA2.toByte(), 0x04.toByte(), 0x01.toByte(), 0x00, 0xFD.toByte()))
    }

    override suspend fun stop(): Boolean {
        return setSpeed(0f)
    }

    override suspend fun setSpeed(speed: Float): Boolean {
        val s = (speed * 10).toInt().toByte()
        return sendCommand(byteArrayOf(0xF7.toByte(), 0xA2.toByte(), 0x01.toByte(), s, 0x00, 0xFD.toByte()))
    }

    override suspend fun setMode(mode: TreadmillMode): Boolean {
        val m = when (mode) {
            TreadmillMode.AUTO -> 0x00
            TreadmillMode.MANUAL -> 0x01
            TreadmillMode.STANDBY -> 0x02
        }.toByte()
        return sendCommand(byteArrayOf(0xF7.toByte(), 0xA2.toByte(), 0x02.toByte(), m, 0x00, 0xFD.toByte()))
    }

    override suspend fun setUnitSystem(unitSystem: UnitSystem): Boolean {
        desiredUnitSystem = unitSystem
        return applyUnitSystem(unitSystem)
    }

    private fun applyUnitSystem(unitSystem: UnitSystem): Boolean {
        val useMiles = if (unitSystem == UnitSystem.Imperial) 1 else 0
        return setPreferenceInt(PREF_UNITS, useMiles)
    }

    private fun sendCommand(cmd: ByteArray): Boolean {
        val fixed = fixCrc(cmd)
        Timber.d("Sending command: ${fixed.joinToString { it.toString(16) }}")
        return connection?.writeCharacteristic(SERVICE_UUID, WRITE_CHAR_UUID, fixed) ?: false
    }

    private fun setPreferenceInt(key: Int, value: Int, type: Int = 0): Boolean {
        val command = byteArrayOf(
            0xF7.toByte(),
            0xA6.toByte(),
            key.toByte(),
            type.toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
            0x00,
            0xFD.toByte()
        )
        return sendCommand(command)
    }

    private fun fixCrc(cmd: ByteArray): ByteArray {
        var sum = 0
        for (i in 1 until cmd.size - 2) {
            sum += cmd[i].toInt() and 0xFF
        }
        cmd[cmd.size - 2] = (sum % 256).toByte()
        return cmd
    }

    private fun handleNotification(data: ByteArray) {
        if (data.size < 20 || data[0] != 0xF8.toByte() || data[1] != 0xA2.toByte()) {
            return
        }

        val state = when (data[2].toInt() and 0xFF) {
            0 -> TreadmillState.STANDBY
            1 -> TreadmillState.MANUAL
            2 -> TreadmillState.AUTOMATIC
            else -> TreadmillState.STANDBY
        }
        
        val speed = (data[3].toInt() and 0xFF) / 10f
        val mode = when (data[4].toInt() and 0xFF) {
            0 -> TreadmillMode.AUTO
            1 -> TreadmillMode.MANUAL
            2 -> TreadmillMode.STANDBY
            else -> TreadmillMode.MANUAL
        }
        
        val time = (data[5].toInt() and 0xFF shl 16) or (data[6].toInt() and 0xFF shl 8) or (data[7].toInt() and 0xFF)
        val distance = (data[8].toInt() and 0xFF shl 16) or (data[9].toInt() and 0xFF shl 8) or (data[10].toInt() and 0xFF)
        val steps = (data[11].toInt() and 0xFF shl 16) or (data[12].toInt() and 0xFF shl 8) or (data[13].toInt() and 0xFF)
        val unitSystem = parseUnitSystem(data)

        _status.value = TreadmillStatus(state, speed, mode, time, distance, steps, unitSystem)
        syncUnitSystemIfNeeded(unitSystem)
    }

    private fun parseUnitSystem(data: ByteArray): UnitSystem? {
        val unitByte = data.getOrNull(18)?.toInt()?.and(0xFF) ?: return null
        return when (unitByte) {
            0 -> UnitSystem.Metric
            1 -> UnitSystem.Imperial
            else -> null
        }
    }

    private fun syncUnitSystemIfNeeded(reportedUnitSystem: UnitSystem?) {
        if (reportedUnitSystem == null || reportedUnitSystem == desiredUnitSystem || isSyncingUnitSystem) return

        Timber.w("WalkingPad unit system diverged. Stored=$desiredUnitSystem, reported=$reportedUnitSystem")
        isSyncingUnitSystem = true
        try {
            applyUnitSystem(desiredUnitSystem)
        } finally {
            isSyncingUnitSystem = false
        }
    }

    companion object {
        private const val PREF_UNITS = 8
    }
}
