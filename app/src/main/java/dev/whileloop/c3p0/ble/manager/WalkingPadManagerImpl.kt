package dev.whileloop.c3p0.ble.manager

import android.content.Context
import android.os.SystemClock
import dev.whileloop.c3p0.ble.controller.BleConnection
import dev.whileloop.c3p0.ble.diagnostic.BleErrorReporter
import dev.whileloop.c3p0.ble.model.*
import dev.whileloop.c3p0.data.model.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.*
import javax.inject.Inject

class WalkingPadManagerImpl @Inject constructor(
    private val context: Context,
    private val errorReporter: BleErrorReporter
) : TreadmillManager {

    private var connection: BleConnection? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectionStateJob: Job? = null
    private var statusPollingJob: Job? = null
    private val commandMutex = Mutex()
    private var lastCommandElapsedMillis = 0L
    private var lastStatusReceivedElapsedMillis = 0L
    private var lastRawNotificationHex: String? = null
    private val protocolReady = MutableStateFlow(false)
    private val _status = MutableStateFlow(TreadmillStatus())
    override val status: StateFlow<TreadmillStatus> = _status.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private var desiredUnitSystem: UnitSystem = UnitSystem.Imperial
    private var isSyncingUnitSystem = false

    private val SERVICE_UUID = UUID.fromString("0000fe00-0000-1000-8000-00805f9b34fb")
    private val NOTIFY_CHAR_UUID = UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb")
    private val WRITE_CHAR_UUID = UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb")

    override suspend fun connect(address: String): Boolean {
        connectionStateJob?.cancel()
        connection?.close()
        stopStatusPolling()
        protocolReady.value = false
        _connectionState.value = ConnectionState.CONNECTING
        connection = BleConnection(context, address, errorReporter).apply {
            onNotificationReceived = { uuid, data ->
                if (uuid == NOTIFY_CHAR_UUID) {
                    handleNotification(data)
                }
            }
            onServicesDiscovered = {
                val notificationsEnabled = enableNotifications(SERVICE_UUID, NOTIFY_CHAR_UUID)
                Timber.d("WalkingPad notifications enabled: $notificationsEnabled")
                if (notificationsEnabled) {
                    scope.launch {
                        delay(PROTOCOL_READY_DELAY_MS)
                        protocolReady.value = true
                        _connectionState.value = ConnectionState.CONNECTED
                        startStatusPolling()
                    }
                } else {
                    errorReporter.report(
                        "WalkingPad Bluetooth",
                        "WalkingPad notifications were not enabled",
                        "address=$address"
                    )
                }
            }
        }
        
        val result = connection?.connect() ?: false
        if (result) {
            _connectionState.value = ConnectionState.CONNECTING
            connection?.let { bleConnection ->
                connectionStateJob?.cancel()
                connectionStateJob = scope.launch {
                    bleConnection.connectionState.collect { state ->
                        when (state) {
                            ConnectionState.CONNECTED -> {
                                if (!protocolReady.value) {
                                    _connectionState.value = ConnectionState.CONNECTING
                                }
                            }
                            ConnectionState.DISCONNECTED -> {
                                protocolReady.value = false
                                _connectionState.value = ConnectionState.DISCONNECTED
                                stopStatusPolling()
                            }
                            else -> _connectionState.value = state
                        }
                    }
                }
            }
        }
        if (!result) {
            errorReporter.report("WalkingPad Bluetooth", "Connect request failed", "address=$address")
        }
        return result
    }

    override suspend fun disconnect() {
        connectionStateJob?.cancel()
        connectionStateJob = null
        stopStatusPolling()
        protocolReady.value = false
        connection?.disconnect()
        connection = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun start(): Boolean {
        return sendControlCommand(
            label = "start belt",
            cmd = byteArrayOf(0xF7.toByte(), 0xA2.toByte(), 0x04.toByte(), 0x01.toByte(), 0x00, 0xFD.toByte()),
            isApplied = { status -> status.state != TreadmillState.STANDBY }
        )
    }

    override suspend fun stop(): Boolean {
        return setSpeed(0f)
    }

    override suspend fun setSpeed(speed: Float): Boolean {
        val targetSpeed = speed.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)
        val s = (targetSpeed * 10).toInt().toByte()
        return sendControlCommand(
            label = "set speed %.1f km/h".format(Locale.US, targetSpeed),
            cmd = byteArrayOf(0xF7.toByte(), 0xA2.toByte(), 0x01.toByte(), s, 0x00, 0xFD.toByte()),
            isApplied = { status -> kotlin.math.abs(status.speed - targetSpeed) <= SPEED_APPLIED_TOLERANCE_KMH }
        )
    }

    override suspend fun setMode(mode: TreadmillMode): Boolean {
        val m = when (mode) {
            TreadmillMode.AUTO -> 0x00
            TreadmillMode.MANUAL -> 0x01
            TreadmillMode.STANDBY -> 0x02
        }.toByte()
        return sendControlCommand(
            label = "set mode $mode",
            cmd = byteArrayOf(0xF7.toByte(), 0xA2.toByte(), 0x02.toByte(), m, 0x00, 0xFD.toByte()),
            isApplied = { status -> status.mode == mode }
        )
    }

    override suspend fun setUnitSystem(unitSystem: UnitSystem): Boolean {
        desiredUnitSystem = unitSystem
        return applyUnitSystem(unitSystem)
    }

    private suspend fun applyUnitSystem(unitSystem: UnitSystem): Boolean {
        val useMiles = if (unitSystem == UnitSystem.Imperial) 1 else 0
        return setPreferenceInt(PREF_UNITS, useMiles)
    }

    private suspend fun askStatus(): Boolean =
        sendCommand(byteArrayOf(0xF7.toByte(), 0xA2.toByte(), 0x00, 0x00, 0x00, 0xFD.toByte()))

    private suspend fun askParams(): Boolean =
        sendCommand(
            byteArrayOf(
                0xF7.toByte(),
                0xA6.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0xFD.toByte()
            )
        )

    private fun startStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = scope.launch {
            delay(INITIAL_POLL_DELAY_MS)
            askParams()
            while (isActive) {
                askStatus()
                delay(STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    private suspend fun sendCommand(cmd: ByteArray): Boolean = commandMutex.withLock {
        if (!awaitProtocolReady()) {
            val fixed = fixCrc(cmd.copyOf())
            errorReporter.report(
                "WalkingPad Bluetooth",
                "WalkingPad protocol is not ready",
                "command=${fixed.toHexString()} connection=${connectionState.value}"
            )
            return@withLock false
        }

        val now = SystemClock.elapsedRealtime()
        val elapsedSinceLastCommand = now - lastCommandElapsedMillis
        if (lastCommandElapsedMillis > 0L && elapsedSinceLastCommand < MIN_COMMAND_SPACING_MS) {
            delay(MIN_COMMAND_SPACING_MS - elapsedSinceLastCommand)
        }

        val fixed = fixCrc(cmd.copyOf())
        Timber.d("Sending WalkingPad command: ${fixed.toHexString()}")
        val sent = connection?.writeCharacteristic(SERVICE_UUID, WRITE_CHAR_UUID, fixed) ?: false
        if (sent) {
            lastCommandElapsedMillis = SystemClock.elapsedRealtime()
        } else {
            Timber.w("WalkingPad command write was not accepted by Android Bluetooth stack")
            errorReporter.report(
                "WalkingPad Bluetooth",
                "Command write was not accepted by Android Bluetooth",
                fixed.toHexString()
            )
        }
        sent
    }

    private suspend fun awaitProtocolReady(): Boolean =
        protocolReady.value ||
            withTimeoutOrNull(PROTOCOL_READY_TIMEOUT_MS) {
                protocolReady.filter { it }.first()
            } == true

    private suspend fun sendControlCommand(
        label: String,
        cmd: ByteArray,
        isApplied: (TreadmillStatus) -> Boolean
    ): Boolean {
        val previousStatusAt = lastStatusReceivedElapsedMillis
        val sent = sendCommand(cmd)
        if (sent) {
            val commandHex = fixCrc(cmd.copyOf()).toHexString()
            scope.launch {
                delay(COMMAND_REPLY_TIMEOUT_MS)
                val latestStatus = status.value
                val statusUpdated = lastStatusReceivedElapsedMillis > previousStatusAt
                val applied = statusUpdated && isApplied(latestStatus)
                if (!applied) {
                    errorReporter.report(
                        "WalkingPad Bluetooth",
                        if (statusUpdated) {
                            "WalkingPad did not apply $label"
                        } else {
                            "No status reply after $label"
                        },
                        commandFailureDetail(commandHex, latestStatus)
                    )
                }
            }
        }
        return sent
    }

    private fun commandFailureDetail(commandHex: String, status: TreadmillStatus): String =
        buildString {
            append("command=")
            append(commandHex)
            append(" connection=")
            append(connectionState.value)
            append(" status=")
            append("state=${status.state},mode=${status.mode},speed=${status.speed}")
            lastRawNotificationHex?.let {
                append(" lastNotification=")
                append(it)
            }
        }

    private suspend fun setPreferenceInt(key: Int, value: Int, type: Int = 0): Boolean {
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
        lastRawNotificationHex = data.toHexString()
        if (data.size < 2 || data[0] != 0xF8.toByte()) {
            return
        }

        when (data[1].toInt() and 0xFF) {
            0xA2 -> handleStatusNotification(data)
            0xA6 -> handleParamsNotification(data)
        }
    }

    private fun handleStatusNotification(data: ByteArray) {
        if (data.size < 15) return
        lastStatusReceivedElapsedMillis = SystemClock.elapsedRealtime()

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
        
        val time = readUInt24(data, 5)
        val distance = readUInt24(data, 8)
        val steps = readUInt24(data, 11)

        _status.value = TreadmillStatus(
            state = state,
            speed = speed,
            mode = mode,
            time = time,
            distance = distance,
            steps = steps,
            unitSystem = _status.value.unitSystem
        )
    }

    private fun handleParamsNotification(data: ByteArray) {
        if (data.size < 14) return

        val reportedUnitSystem = when (data[13].toInt() and 0xFF) {
            0 -> UnitSystem.Metric
            1 -> UnitSystem.Imperial
            else -> null
        }
        _status.value = _status.value.copy(unitSystem = reportedUnitSystem)
        syncUnitSystemIfNeeded(reportedUnitSystem)
    }

    private fun readUInt24(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)

    private fun syncUnitSystemIfNeeded(reportedUnitSystem: UnitSystem?) {
        if (reportedUnitSystem == null || reportedUnitSystem == desiredUnitSystem || isSyncingUnitSystem) return

        Timber.w("WalkingPad unit system diverged. Stored=$desiredUnitSystem, reported=$reportedUnitSystem")
        isSyncingUnitSystem = true
        scope.launch {
            try {
                applyUnitSystem(desiredUnitSystem)
            } finally {
                isSyncingUnitSystem = false
            }
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = " ") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    companion object {
        private const val PREF_UNITS = 8
        private const val MIN_SPEED_KMH = 0f
        private const val MAX_SPEED_KMH = 6f
        private const val INITIAL_POLL_DELAY_MS = 250L
        private const val MIN_COMMAND_SPACING_MS = 700L
        private const val STATUS_POLL_INTERVAL_MS = 750L
        private const val COMMAND_REPLY_TIMEOUT_MS = 2_500L
        private const val SPEED_APPLIED_TOLERANCE_KMH = 0.05f
        private const val PROTOCOL_READY_DELAY_MS = 500L
        private const val PROTOCOL_READY_TIMEOUT_MS = 5_000L
    }
}
