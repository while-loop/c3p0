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
    private var protocolReadyWatchdogJob: Job? = null
    private val commandMutex = Mutex()
    private var lastCommandElapsedMillis = 0L
    private var lastStatusReceivedElapsedMillis = 0L
    private var lastRawNotificationHex: String? = null
    private var activeProtocol = WalkingPadProtocol.LegacyKingsmith
    private val protocolReady = MutableStateFlow(false)
    private val _status = MutableStateFlow(TreadmillStatus())
    override val status: StateFlow<TreadmillStatus> = _status.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _supportsNativeAutoMode = MutableStateFlow(false)
    override val supportsNativeAutoMode: StateFlow<Boolean> = _supportsNativeAutoMode.asStateFlow()

    private var desiredUnitSystem: UnitSystem = UnitSystem.Imperial
    private var isSyncingUnitSystem = false

    private val SERVICE_UUID = UUID.fromString("0000fe00-0000-1000-8000-00805f9b34fb")
    private val NOTIFY_CHAR_UUID = UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb")
    private val WRITE_CHAR_UUID = UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb")
    private val FTMS_SERVICE_UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
    private val FTMS_TREADMILL_DATA_UUID = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb")
    private val FTMS_CONTROL_POINT_UUID = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")

    override suspend fun connect(address: String): Boolean {
        connectionStateJob?.cancel()
        connection?.close()
        stopStatusPolling()
        protocolReadyWatchdogJob?.cancel()
        protocolReady.value = false
        _supportsNativeAutoMode.value = false
        _connectionState.value = ConnectionState.CONNECTING
        connection = BleConnection(
            context = context,
            address = address,
            errorReporter = errorReporter,
            requiredServiceUuid = SERVICE_UUID,
            fallbackServiceUuids = setOf(FTMS_SERVICE_UUID),
            refreshGattOnConnect = true,
            preferWriteWithoutResponse = true
        ).apply {
            onNotificationReceived = { uuid, data ->
                when (uuid) {
                    NOTIFY_CHAR_UUID -> handleNotification(data)
                    FTMS_TREADMILL_DATA_UUID -> handleFtmsTreadmillData(data)
                    FTMS_CONTROL_POINT_UUID -> handleFtmsControlPoint(data)
                }
            }
            onServicesDiscovered = {
                when {
                    hasService(SERVICE_UUID) -> activateLegacyProtocol(address)
                    hasService(FTMS_SERVICE_UUID) -> activateFtmsProtocol(address)
                    else -> {
                        errorReporter.report(
                            "WalkingPad Bluetooth",
                            "No supported WalkingPad protocol service found",
                            "address=$address services=${serviceSummary()}"
                        )
                    }
                }
            }
        }
        
        val result = connection?.connect() ?: false
        if (result) {
            _connectionState.value = ConnectionState.CONNECTING
            startProtocolReadyWatchdog(address)
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
                                _supportsNativeAutoMode.value = false
                                _connectionState.value = ConnectionState.DISCONNECTED
                                stopStatusPolling()
                                protocolReadyWatchdogJob?.cancel()
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
        protocolReadyWatchdogJob?.cancel()
        protocolReady.value = false
        _supportsNativeAutoMode.value = false
        connection?.disconnect()
        connection = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun start(): Boolean {
        if (activeProtocol == WalkingPadProtocol.FitnessMachineService) {
            requestFtmsControl()
            val startSpeed = status.value.speed.coerceAtLeast(MIN_MOVING_SPEED_KMH)
            val started = sendCommand(byteArrayOf(FTMS_OP_START_OR_RESUME))
            if (!started) return false
            delay(START_COUNTDOWN_SPEED_DELAY_MS)
            return sendFtmsTargetSpeed(startSpeed)
        } else {
            if (status.value.mode == TreadmillMode.STANDBY && !setMode(TreadmillMode.MANUAL)) {
                return false
            }
            val startSpeed = status.value.speed.coerceAtLeast(MIN_MOVING_SPEED_KMH)
            val started = sendCommand(
                byteArrayOf(0xF7.toByte(), 0xA2.toByte(), 0x04.toByte(), 0x01.toByte(), 0x00, 0xFD.toByte())
            )
            if (!started) return false
            delay(START_COUNTDOWN_SPEED_DELAY_MS)
            return sendLegacySpeed(startSpeed)
        }
    }

    override suspend fun stop(): Boolean {
        return if (activeProtocol == WalkingPadProtocol.FitnessMachineService) {
            sendControlCommand(
                label = "stop belt",
                cmd = byteArrayOf(FTMS_OP_STOP_OR_PAUSE, FTMS_STOP),
                isApplied = { status -> status.state == TreadmillState.STOPPED || status.speed <= SPEED_APPLIED_TOLERANCE_KMH }
            )
        } else {
            sendLegacySpeed(0f)
        }
    }

    override suspend fun setSpeed(speed: Float): Boolean {
        val targetSpeed = speed.coerceIn(MIN_MOVING_SPEED_KMH, MAX_SPEED_KMH)
        if (activeProtocol == WalkingPadProtocol.FitnessMachineService) {
            requestFtmsControl()
            return sendFtmsTargetSpeed(targetSpeed)
        }

        return sendLegacySpeed(targetSpeed)
    }

    private suspend fun sendLegacySpeed(
        targetSpeed: Float,
        verifyApplied: Boolean = true
    ): Boolean {
        val s = (targetSpeed * 10).toInt().toByte()
        val cmd = byteArrayOf(0xF7.toByte(), 0xA2.toByte(), 0x01.toByte(), s, 0x00, 0xFD.toByte())
        return sendSpeedCommand(
            targetSpeed = targetSpeed,
            cmd = cmd,
            verifyApplied = verifyApplied
        )
    }

    private suspend fun sendFtmsTargetSpeed(
        targetSpeed: Float,
        verifyApplied: Boolean = true
    ): Boolean {
        val speedHundredths = (targetSpeed * 100).toInt().coerceIn(0, UShort.MAX_VALUE.toInt())
        val cmd = byteArrayOf(
            FTMS_OP_SET_TARGET_SPEED,
            (speedHundredths and 0xFF).toByte(),
            ((speedHundredths shr 8) and 0xFF).toByte()
        )
        return sendSpeedCommand(
            targetSpeed = targetSpeed,
            cmd = cmd,
            verifyApplied = verifyApplied
        )
    }

    private suspend fun sendSpeedCommand(
        targetSpeed: Float,
        cmd: ByteArray,
        verifyApplied: Boolean
    ): Boolean =
        if (verifyApplied) {
            sendControlCommand(
                label = "set speed %.1f km/h".format(Locale.US, targetSpeed),
                cmd = cmd,
                isApplied = { status -> kotlin.math.abs(status.speed - targetSpeed) <= SPEED_APPLIED_TOLERANCE_KMH }
            )
        } else {
            sendCommand(cmd)
        }

    override suspend fun setMode(mode: TreadmillMode): Boolean {
        if (activeProtocol == WalkingPadProtocol.FitnessMachineService) {
            if (mode != TreadmillMode.MANUAL) {
                errorReporter.report(
                    "WalkingPad Bluetooth",
                    "Native WalkingPad automatic mode is not available over FTMS",
                    "mode=$mode"
                )
                return false
            }
            return true
        }

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
        if (activeProtocol == WalkingPadProtocol.FitnessMachineService) {
            _status.value = _status.value.copy(unitSystem = unitSystem)
            return true
        }

        val useMiles = if (unitSystem == UnitSystem.Imperial) 1 else 0
        return setPreferenceInt(PREF_UNITS, useMiles)
    }

    private suspend fun askStatus(): Boolean =
        if (activeProtocol == WalkingPadProtocol.FitnessMachineService) {
            true
        } else {
        sendCommand(byteArrayOf(0xF7.toByte(), 0xA2.toByte(), 0x00, 0x00, 0x00, 0xFD.toByte()))
        }

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

    private fun startProtocolReadyWatchdog(address: String) {
        protocolReadyWatchdogJob?.cancel()
        protocolReadyWatchdogJob = scope.launch {
            delay(PROTOCOL_READY_WATCHDOG_MS)
            if (!protocolReady.value && connectionState.value == ConnectionState.CONNECTING) {
                errorReporter.report(
                    "WalkingPad Bluetooth",
                    "WalkingPad protocol did not become ready",
                    "address=$address services=${connection?.serviceSummary() ?: "none"}"
                )
            }
        }
    }

    private suspend fun sendCommand(cmd: ByteArray): Boolean = commandMutex.withLock {
        if (!awaitProtocolReady()) {
            val fixed = if (activeProtocol == WalkingPadProtocol.FitnessMachineService) {
                cmd
            } else {
                fixCrc(cmd.copyOf())
            }
            errorReporter.report(
                "WalkingPad Bluetooth",
                "WalkingPad protocol is not ready",
                "command=${fixed.toHexString()} connection=${connectionState.value} services=${connection?.serviceSummary() ?: "none"}"
            )
            return@withLock false
        }

        val now = SystemClock.elapsedRealtime()
        val elapsedSinceLastCommand = now - lastCommandElapsedMillis
        if (lastCommandElapsedMillis > 0L && elapsedSinceLastCommand < MIN_COMMAND_SPACING_MS) {
            delay(MIN_COMMAND_SPACING_MS - elapsedSinceLastCommand)
        }

        val fixed = if (activeProtocol == WalkingPadProtocol.FitnessMachineService) {
            cmd
        } else {
            fixCrc(cmd.copyOf())
        }
        Timber.d("Sending WalkingPad command: ${fixed.toHexString()}")
        val sent = if (activeProtocol == WalkingPadProtocol.FitnessMachineService) {
            connection?.writeCharacteristic(
                FTMS_SERVICE_UUID,
                FTMS_CONTROL_POINT_UUID,
                fixed,
                preferWithoutResponse = false
            ) ?: false
        } else {
            connection?.writeCharacteristic(SERVICE_UUID, WRITE_CHAR_UUID, fixed) ?: false
        }
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
            val commandHex = if (activeProtocol == WalkingPadProtocol.FitnessMachineService) {
                cmd.toHexString()
            } else {
                fixCrc(cmd.copyOf()).toHexString()
            }
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

    private fun activateLegacyProtocol(address: String) {
        activeProtocol = WalkingPadProtocol.LegacyKingsmith
        _supportsNativeAutoMode.value = true
        val notificationsEnabled = connection?.enableNotifications(SERVICE_UUID, NOTIFY_CHAR_UUID) ?: false
        Timber.d("WalkingPad legacy notifications enabled: $notificationsEnabled")
        if (notificationsEnabled) {
            scope.launch {
                delay(PROTOCOL_READY_DELAY_MS)
                protocolReady.value = true
                protocolReadyWatchdogJob?.cancel()
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

    private fun activateFtmsProtocol(address: String) {
        activeProtocol = WalkingPadProtocol.FitnessMachineService
        _supportsNativeAutoMode.value = false
        scope.launch {
            val treadmillNotificationsEnabled =
                connection?.enableNotifications(FTMS_SERVICE_UUID, FTMS_TREADMILL_DATA_UUID) ?: false
            delay(GATT_DESCRIPTOR_WRITE_DELAY_MS)
            val controlPointIndicationsEnabled =
                connection?.enableIndications(FTMS_SERVICE_UUID, FTMS_CONTROL_POINT_UUID) ?: false
            Timber.d(
                "WalkingPad FTMS ready checks. treadmillData=$treadmillNotificationsEnabled " +
                    "controlPoint=$controlPointIndicationsEnabled"
            )

            if (treadmillNotificationsEnabled && controlPointIndicationsEnabled) {
                protocolReady.value = true
                protocolReadyWatchdogJob?.cancel()
                _connectionState.value = ConnectionState.CONNECTED
                requestFtmsControl()
            } else {
                errorReporter.report(
                    "WalkingPad Bluetooth",
                    "FTMS notifications or control indications were not enabled",
                    "address=$address treadmillData=$treadmillNotificationsEnabled controlPoint=$controlPointIndicationsEnabled"
                )
            }
        }
    }

    private suspend fun requestFtmsControl(): Boolean =
        sendCommand(byteArrayOf(FTMS_OP_REQUEST_CONTROL))

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

    private fun handleFtmsControlPoint(data: ByteArray) {
        lastRawNotificationHex = data.toHexString()
        if (data.size >= 3 && data[0] == FTMS_OP_RESPONSE_CODE) {
            Timber.d(
                "FTMS control response request=${data[1].toInt() and 0xFF} " +
                    "result=${data[2].toInt() and 0xFF}"
            )
        }
    }

    private fun handleFtmsTreadmillData(data: ByteArray) {
        lastRawNotificationHex = data.toHexString()
        if (data.size < 4) return

        lastStatusReceivedElapsedMillis = SystemClock.elapsedRealtime()
        val flags = readUInt16LE(data, 0)
        var offset = 2
        val hasInstantSpeed = flags and FTMS_TREADMILL_MORE_DATA_FLAG == 0
        val speed = if (hasInstantSpeed && data.size >= offset + 2) {
            readUInt16LE(data, offset) / 100f
        } else {
            status.value.speed
        }
        if (hasInstantSpeed) offset += 2
        if (flags and FTMS_TREADMILL_AVERAGE_SPEED_FLAG != 0) offset += 2

        val distance = if (flags and FTMS_TREADMILL_TOTAL_DISTANCE_FLAG != 0 && data.size >= offset + 3) {
            val meters = readUInt24LE(data, offset)
            offset += 3
            meters / 10
        } else {
            status.value.distance
        }

        if (flags and FTMS_TREADMILL_INCLINATION_FLAG != 0) offset += 4
        if (flags and FTMS_TREADMILL_ELEVATION_GAIN_FLAG != 0) offset += 4
        if (flags and FTMS_TREADMILL_INSTANT_PACE_FLAG != 0) offset += 1
        if (flags and FTMS_TREADMILL_AVERAGE_PACE_FLAG != 0) offset += 1
        if (flags and FTMS_TREADMILL_ENERGY_FLAG != 0) offset += 5
        if (flags and FTMS_TREADMILL_HEART_RATE_FLAG != 0) offset += 1
        if (flags and FTMS_TREADMILL_METABOLIC_EQUIVALENT_FLAG != 0) offset += 1

        val elapsedTime = if (flags and FTMS_TREADMILL_ELAPSED_TIME_FLAG != 0 && data.size >= offset + 2) {
            readUInt16LE(data, offset)
        } else {
            status.value.time
        }

        _status.value = status.value.copy(
            state = if (speed > SPEED_APPLIED_TOLERANCE_KMH) TreadmillState.ACTIVE else TreadmillState.STOPPED,
            speed = speed,
            mode = TreadmillMode.MANUAL,
            time = elapsedTime,
            distance = distance
        )
    }

    private fun handleStatusNotification(data: ByteArray) {
        if (data.size < 15) return
        lastStatusReceivedElapsedMillis = SystemClock.elapsedRealtime()

        val state = when (data[2].toInt() and 0xFF) {
            0 -> TreadmillState.STOPPED
            1 -> TreadmillState.ACTIVE
            5 -> TreadmillState.STANDBY
            9 -> TreadmillState.STARTING
            else -> TreadmillState.ERROR
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

    private fun readUInt16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt24LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)

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
        private const val MIN_MOVING_SPEED_KMH = 1.60934f
        private const val MAX_SPEED_KMH = 6f
        private const val START_COUNTDOWN_SPEED_DELAY_MS = 3_200L
        private const val INITIAL_POLL_DELAY_MS = 250L
        private const val MIN_COMMAND_SPACING_MS = 700L
        private const val STATUS_POLL_INTERVAL_MS = 750L
        private const val COMMAND_REPLY_TIMEOUT_MS = 2_500L
        private const val SPEED_APPLIED_TOLERANCE_KMH = 0.05f
        private const val PROTOCOL_READY_DELAY_MS = 500L
        private const val PROTOCOL_READY_TIMEOUT_MS = 5_000L
        private const val PROTOCOL_READY_WATCHDOG_MS = 8_000L
        private const val GATT_DESCRIPTOR_WRITE_DELAY_MS = 400L
        private const val FTMS_OP_REQUEST_CONTROL: Byte = 0x00
        private const val FTMS_OP_SET_TARGET_SPEED: Byte = 0x02
        private const val FTMS_OP_START_OR_RESUME: Byte = 0x07
        private const val FTMS_OP_STOP_OR_PAUSE: Byte = 0x08
        private const val FTMS_OP_RESPONSE_CODE: Byte = 0x80.toByte()
        private const val FTMS_STOP: Byte = 0x01
        private const val FTMS_TREADMILL_MORE_DATA_FLAG = 0x0001
        private const val FTMS_TREADMILL_AVERAGE_SPEED_FLAG = 0x0002
        private const val FTMS_TREADMILL_TOTAL_DISTANCE_FLAG = 0x0004
        private const val FTMS_TREADMILL_INCLINATION_FLAG = 0x0008
        private const val FTMS_TREADMILL_ELEVATION_GAIN_FLAG = 0x0010
        private const val FTMS_TREADMILL_INSTANT_PACE_FLAG = 0x0020
        private const val FTMS_TREADMILL_AVERAGE_PACE_FLAG = 0x0040
        private const val FTMS_TREADMILL_ENERGY_FLAG = 0x0080
        private const val FTMS_TREADMILL_HEART_RATE_FLAG = 0x0100
        private const val FTMS_TREADMILL_METABOLIC_EQUIVALENT_FLAG = 0x0200
        private const val FTMS_TREADMILL_ELAPSED_TIME_FLAG = 0x0400
    }

    private enum class WalkingPadProtocol {
        LegacyKingsmith,
        FitnessMachineService
    }
}
