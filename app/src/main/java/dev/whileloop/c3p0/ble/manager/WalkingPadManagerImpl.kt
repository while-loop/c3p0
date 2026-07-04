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
    private var ksEncryptedHandshakeIndex = 0
    private var ksEncryptedReadBuffer = ByteArray(0)
    private var activeKsEncryptedTable: String? = null
    private var candidateKsEncryptedTables = KingsmithEncryptedProtocol.encryptionTables.toSet()
    private var activeKsEncryptedReadCharSubstring: String? = null
    private var activeKsEncryptedWriteCharSubstring: String? = null
    private val commandMutex = Mutex()
    private var lastCommandElapsedMillis = 0L
    private var lastStatusReceivedElapsedMillis = 0L
    private var lastRawNotificationHex: String? = null
    private var ftmsControlRequested = false
    private val protocolReady = MutableStateFlow(false)
    private val legacyProtocolReady = MutableStateFlow(false)
    private val ksEncryptedProtocolReady = MutableStateFlow(false)
    private val ftmsProtocolReady = MutableStateFlow(false)
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
        legacyProtocolReady.value = false
        ksEncryptedProtocolReady.value = false
        ftmsProtocolReady.value = false
        ftmsControlRequested = false
        resetKsEncryptedState()
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
                    else -> {
                        if (isKsEncryptedReadCharacteristic(uuid)) {
                            handleKsEncryptedNotification(data)
                        }
                    }
                }
            }
            onServicesDiscovered = {
                val hasLegacyService = hasService(SERVICE_UUID)
                val hasFtmsService = hasService(FTMS_SERVICE_UUID)
                if (!hasLegacyService && !hasFtmsService) {
                    errorReporter.report(
                        "WalkingPad Bluetooth",
                        "No supported WalkingPad protocol service found",
                        "address=$address services=${serviceSummary()}"
                    )
                }
                scope.launch {
                    if (hasLegacyService) {
                        activateLegacyProtocol(address)
                    }
                    activateKsEncryptedProtocolIfAvailable()
                    if (hasLegacyService && hasFtmsService) {
                        delay(GATT_DESCRIPTOR_WRITE_DELAY_MS)
                    }
                    if (hasFtmsService) {
                        activateFtmsProtocol(address)
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
                                resetProtocolState()
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
        resetProtocolState()
        _supportsNativeAutoMode.value = false
        connection?.disconnect()
        connection = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun start(): Boolean {
        if (ksEncryptedProtocolReady.value) {
            if (status.value.mode == TreadmillMode.STANDBY && !setKsEncryptedMode(TreadmillMode.MANUAL)) {
                return false
            }
            val startSpeed = status.value.speed.coerceAtLeast(MIN_MOVING_SPEED_KMH)
            val started = sendKsEncryptedControlCommand(
                label = "start belt",
                command = KingsmithEncryptedProtocol.START_COMMAND,
                isApplied = { status -> status.state == TreadmillState.ACTIVE || status.state == TreadmillState.STARTING }
            )
            if (!started) return false
            delay(START_COUNTDOWN_SPEED_DELAY_MS)
            return sendKsEncryptedSpeed(startSpeed)
        } else if (ftmsProtocolReady.value) {
            if (!ensureFtmsControlRequested()) return false
            val startSpeed = status.value.speed.coerceAtLeast(MIN_MOVING_SPEED_KMH)
            val started = sendCommand(
                cmd = byteArrayOf(FTMS_OP_START_OR_RESUME),
                protocol = WalkingPadProtocol.FitnessMachineService
            )
            if (!started) return false
            delay(START_COUNTDOWN_SPEED_DELAY_MS)
            return sendFtmsTargetSpeed(startSpeed)
        } else {
            if (status.value.mode == TreadmillMode.STANDBY && !setMode(TreadmillMode.MANUAL)) {
                return false
            }
            val startSpeed = status.value.speed.coerceAtLeast(MIN_MOVING_SPEED_KMH)
            val started = sendCommand(
                cmd = byteArrayOf(0xF7.toByte(), 0xA2.toByte(), 0x04.toByte(), 0x01.toByte(), 0x00, 0xFD.toByte()),
                protocol = WalkingPadProtocol.LegacyKingsmith
            )
            if (!started) return false
            delay(START_COUNTDOWN_SPEED_DELAY_MS)
            return sendLegacySpeed(startSpeed)
        }
    }

    override suspend fun stop(): Boolean {
        return if (ksEncryptedProtocolReady.value) {
            stopKsEncryptedBelt()
        } else if (ftmsProtocolReady.value) {
            stopFtmsBelt()
        } else {
            pause()
        }
    }

    override suspend fun pause(): Boolean {
        return if (ksEncryptedProtocolReady.value) {
            stopKsEncryptedBelt()
        } else if (ftmsProtocolReady.value) {
            pauseFtmsBelt()
        } else {
            sendLegacySpeed(0f)
        }
    }

    private suspend fun pauseFtmsBelt(): Boolean {
        if (!ensureFtmsControlRequested()) return false
        return sendCommand(
            cmd = byteArrayOf(FTMS_OP_STOP_OR_PAUSE, FTMS_PAUSE),
            protocol = WalkingPadProtocol.FitnessMachineService
        )
    }

    private suspend fun stopFtmsBelt(): Boolean {
        if (!ensureFtmsControlRequested()) return false

        if (!isBeltStopped(status.value)) {
            pauseFtmsBelt()
            if (!awaitStatusApplied(FTMS_BELT_COAST_STOP_TIMEOUT_MS, ::isBeltStopped)) {
                errorReporter.report(
                    "WalkingPad Bluetooth",
                    "WalkingPad is still moving; wait for the belt to stop before stopping",
                    commandFailureDetail(
                        protocol = WalkingPadProtocol.FitnessMachineService,
                        commandHex = "08 02",
                        status = status.value
                    )
                )
                return false
            }
        }

        val stopSent = sendCommand(
            cmd = byteArrayOf(FTMS_OP_STOP_OR_PAUSE, FTMS_STOP),
            protocol = WalkingPadProtocol.FitnessMachineService
        )
        if (stopSent && awaitStatusApplied(FTMS_STOP_APPLY_TIMEOUT_MS, ::isBeltStopped)) {
            return true
        }

        val resetSent = sendCommand(
            cmd = byteArrayOf(FTMS_OP_RESET),
            protocol = WalkingPadProtocol.FitnessMachineService
        )
        if (resetSent && awaitStatusApplied(FTMS_STOP_APPLY_TIMEOUT_MS, ::isBeltStopped)) {
            return true
        }

        if (stopKsEncryptedBelt()) {
            return true
        }

        errorReporter.report(
            "WalkingPad Bluetooth",
            "WalkingPad did not apply FTMS stop sequence",
            commandFailureDetail(
                protocol = WalkingPadProtocol.FitnessMachineService,
                commandHex = "08 02; 08 01; 01; KS props runState 0",
                status = status.value
            ) + " ksEncryptedWrites=${availableKsEncryptedWriteChars().joinToString().ifBlank { "none" }}"
        )
        return false
    }

    private suspend fun stopKsEncryptedBelt(): Boolean {
        if (sendKsEncryptedControlCommand(
                label = "stop belt",
                command = KingsmithEncryptedProtocol.STOP_COMMAND,
                isApplied = ::isBeltStopped
            ) && awaitStatusApplied(KS_ENCRYPTED_STOP_APPLY_TIMEOUT_MS, ::isBeltStopped)
        ) {
            return true
        }

        return sendKsEncryptedCommandVariants(
            command = KingsmithEncryptedProtocol.STOP_COMMAND,
            requireReady = ksEncryptedProtocolReady.value
        ) &&
            awaitStatusApplied(KS_ENCRYPTED_STOP_APPLY_TIMEOUT_MS, ::isBeltStopped)
    }

    override suspend fun setSpeed(speed: Float): Boolean {
        val targetSpeed = speed.coerceIn(MIN_MOVING_SPEED_KMH, MAX_SPEED_KMH)
        if (ksEncryptedProtocolReady.value) {
            return sendKsEncryptedSpeed(targetSpeed)
        }

        if (ftmsProtocolReady.value) {
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
            protocol = WalkingPadProtocol.LegacyKingsmith,
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
            protocol = WalkingPadProtocol.FitnessMachineService,
            verifyApplied = verifyApplied
        )
    }

    private suspend fun sendKsEncryptedSpeed(
        targetSpeed: Float,
        verifyApplied: Boolean = true
    ): Boolean {
        val command = KingsmithEncryptedProtocol.speedCommand(targetSpeed)
        return if (verifyApplied) {
            sendKsEncryptedControlCommand(
                label = "set speed %.1f km/h".format(Locale.US, targetSpeed),
                command = command,
                minCommandSpacingMs = SPEED_COMMAND_SPACING_MS,
                isApplied = { status -> kotlin.math.abs(status.speed - targetSpeed) <= SPEED_APPLIED_TOLERANCE_KMH }
            )
        } else {
            sendKsEncryptedTextCommand(command, minCommandSpacingMs = SPEED_COMMAND_SPACING_MS)
        }
    }

    private suspend fun sendSpeedCommand(
        targetSpeed: Float,
        cmd: ByteArray,
        protocol: WalkingPadProtocol,
        verifyApplied: Boolean
    ): Boolean =
        if (verifyApplied) {
            sendControlCommand(
                label = "set speed %.1f km/h".format(Locale.US, targetSpeed),
                cmd = cmd,
                protocol = protocol,
                minCommandSpacingMs = SPEED_COMMAND_SPACING_MS,
                isApplied = { status -> kotlin.math.abs(status.speed - targetSpeed) <= SPEED_APPLIED_TOLERANCE_KMH }
            )
        } else {
            sendCommand(cmd, protocol, SPEED_COMMAND_SPACING_MS)
        }

    override suspend fun setMode(mode: TreadmillMode): Boolean {
        if (mode == TreadmillMode.MANUAL && !legacyProtocolReady.value && !ksEncryptedProtocolReady.value) {
            return true
        }

        if (mode == TreadmillMode.AUTO && status.value.state == TreadmillState.ACTIVE) {
            errorReporter.report(
                "WalkingPad Bluetooth",
                "Pause before switching to native WalkingPad automatic mode",
                "mode=$mode state=${status.value.state} speed=${status.value.speed}"
            )
            return false
        }

        if (legacyProtocolReady.value) {
            return setLegacyMode(mode)
        }

        if (ksEncryptedProtocolReady.value) {
            return setKsEncryptedMode(mode)
        }

        errorReporter.report(
            "WalkingPad Bluetooth",
            "Native WalkingPad automatic mode is not available on this connection",
            "mode=$mode services=${connection?.serviceSummary() ?: "none"}"
        )
        return false
    }

    private suspend fun setLegacyMode(mode: TreadmillMode): Boolean {
        val m = when (mode) {
            TreadmillMode.AUTO -> 0x00
            TreadmillMode.MANUAL -> 0x01
            TreadmillMode.STANDBY -> 0x02
        }.toByte()
        return sendControlCommand(
            label = "set mode $mode",
            cmd = byteArrayOf(0xF7.toByte(), 0xA2.toByte(), 0x02.toByte(), m, 0x00, 0xFD.toByte()),
            protocol = WalkingPadProtocol.LegacyKingsmith,
            isApplied = { status -> status.mode == mode }
        )
    }

    private suspend fun setKsEncryptedMode(mode: TreadmillMode): Boolean {
        val command = when (mode) {
            TreadmillMode.AUTO -> KingsmithEncryptedProtocol.AUTO_MODE_COMMAND
            TreadmillMode.MANUAL -> KingsmithEncryptedProtocol.MANUAL_MODE_COMMAND
            TreadmillMode.STANDBY -> KingsmithEncryptedProtocol.STANDBY_MODE_COMMAND
        }
        val sent = sendKsEncryptedControlCommand(
            label = "set mode $mode",
            command = command,
            isApplied = { status -> status.mode == mode }
        )
        if (sent) {
            _status.value = status.value.copy(mode = mode)
        } else {
            errorReporter.report(
                "WalkingPad Bluetooth",
                "WalkingPad did not apply encrypted KS mode command",
                "mode=$mode command=$command ksEncryptedWrites=${availableKsEncryptedWriteChars().joinToString().ifBlank { "none" }}"
            )
        }
        return sent
    }

    override suspend fun setUnitSystem(unitSystem: UnitSystem): Boolean {
        desiredUnitSystem = unitSystem
        return applyUnitSystem(unitSystem)
    }

    private suspend fun applyUnitSystem(unitSystem: UnitSystem): Boolean {
        if (!legacyProtocolReady.value) {
            _status.value = _status.value.copy(unitSystem = unitSystem)
            return true
        }

        val useMiles = if (unitSystem == UnitSystem.Imperial) 1 else 0
        return setPreferenceInt(PREF_UNITS, useMiles)
    }

    private suspend fun askStatus(): Boolean =
        if (!legacyProtocolReady.value) {
            true
        } else {
            sendCommand(
                cmd = byteArrayOf(0xF7.toByte(), 0xA2.toByte(), 0x00, 0x00, 0x00, 0xFD.toByte()),
                protocol = WalkingPadProtocol.LegacyKingsmith
            )
        }

    private suspend fun askKsEncryptedStatus(): Boolean =
        if (!ksEncryptedProtocolReady.value) {
            true
        } else {
            sendKsEncryptedTextCommand(
                command = KingsmithEncryptedProtocol.POLL_PROPS_COMMAND,
                minCommandSpacingMs = SPEED_COMMAND_SPACING_MS
            )
        }

    private suspend fun askParams(): Boolean =
        if (!legacyProtocolReady.value) {
            true
        } else {
            sendCommand(
                cmd = byteArrayOf(
                    0xF7.toByte(),
                    0xA6.toByte(),
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0xFD.toByte()
                ),
                protocol = WalkingPadProtocol.LegacyKingsmith
            )
        }

    private fun startStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = scope.launch {
            delay(INITIAL_POLL_DELAY_MS)
            askParams()
            while (isActive) {
                askStatus()
                askKsEncryptedStatus()
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

    private suspend fun sendCommand(
        cmd: ByteArray,
        protocol: WalkingPadProtocol,
        minCommandSpacingMs: Long = MIN_COMMAND_SPACING_MS
    ): Boolean = commandMutex.withLock {
        if (!awaitProtocolReady(protocol)) {
            val fixed = if (protocol == WalkingPadProtocol.FitnessMachineService) {
                cmd
            } else {
                fixCrc(cmd.copyOf())
            }
            errorReporter.report(
                "WalkingPad Bluetooth",
                "WalkingPad protocol is not ready",
                "protocol=$protocol command=${fixed.toHexString()} connection=${connectionState.value} services=${connection?.serviceSummary() ?: "none"}"
            )
            return@withLock false
        }

        val now = SystemClock.elapsedRealtime()
        val elapsedSinceLastCommand = now - lastCommandElapsedMillis
        if (lastCommandElapsedMillis > 0L && elapsedSinceLastCommand < minCommandSpacingMs) {
            delay(minCommandSpacingMs - elapsedSinceLastCommand)
        }

        val fixed = if (protocol == WalkingPadProtocol.FitnessMachineService) {
            cmd
        } else {
            fixCrc(cmd.copyOf())
        }
        Timber.d("Sending WalkingPad $protocol command: ${fixed.toHexString()}")
        val sent = if (protocol == WalkingPadProtocol.FitnessMachineService) {
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
                "protocol=$protocol command=${fixed.toHexString()}"
            )
        }
        sent
    }

    private suspend fun sendKsEncryptedCommand(
        writeCharSubstring: String,
        cmd: ByteArray,
        minCommandSpacingMs: Long = MIN_COMMAND_SPACING_MS,
        requireReady: Boolean = true
    ): Boolean = commandMutex.withLock {
        if (requireReady && !awaitProtocolReady(WalkingPadProtocol.KingsmithEncrypted)) {
            errorReporter.report(
                "WalkingPad Bluetooth",
                "WalkingPad protocol is not ready",
                "protocol=${WalkingPadProtocol.KingsmithEncrypted} command=${cmd.toHexString()} connection=${connectionState.value}"
            )
            return@withLock false
        }

        val now = SystemClock.elapsedRealtime()
        val elapsedSinceLastCommand = now - lastCommandElapsedMillis
        if (lastCommandElapsedMillis > 0L && elapsedSinceLastCommand < minCommandSpacingMs) {
            delay(minCommandSpacingMs - elapsedSinceLastCommand)
        }

        Timber.d("Sending WalkingPad encrypted KS command: ${cmd.toHexString()} char~=$writeCharSubstring")
        var sent = true
        var offset = 0
        while (offset < cmd.size && sent) {
            val end = (offset + KS_ENCRYPTED_WRITE_CHUNK_SIZE).coerceAtMost(cmd.size)
            val chunk = cmd.copyOfRange(offset, end)
            sent = connection?.writeCharacteristicByUuidSubstring(
                charUuidSubstring = writeCharSubstring,
                data = chunk,
                preferWithoutResponse = false
            ) ?: false
            offset = end
            if (sent && offset < cmd.size) {
                delay(KS_ENCRYPTED_WRITE_CHUNK_DELAY_MS)
            }
        }
        if (sent && cmd.isNotEmpty()) {
            lastCommandElapsedMillis = SystemClock.elapsedRealtime()
        }
        sent
    }

    private suspend fun sendKsEncryptedTextCommand(
        command: String,
        minCommandSpacingMs: Long = MIN_COMMAND_SPACING_MS,
        requireReady: Boolean = true
    ): Boolean {
        val writeChar = activeKsEncryptedWriteCharSubstring ?: availableKsEncryptedWriteChars().firstOrNull()
        if (writeChar == null) {
            errorReporter.report(
                "WalkingPad Bluetooth",
                "Encrypted KS write characteristic is not available",
                "command=$command services=${connection?.serviceSummary() ?: "none"}"
            )
            return false
        }
        val table = activeKsEncryptedTable
        if (table == null) {
            var anySent = false
            KingsmithEncryptedProtocol.commandPayloads(command).forEach { payload ->
                if (sendKsEncryptedCommand(
                        writeCharSubstring = writeChar,
                        cmd = payload,
                        minCommandSpacingMs = minCommandSpacingMs,
                        requireReady = requireReady
                    )
                ) {
                    anySent = true
                }
            }
            return anySent
        }
        val payload = KingsmithEncryptedProtocol.encode(command, table)
        return sendKsEncryptedCommand(
            writeCharSubstring = writeChar,
            cmd = payload,
            minCommandSpacingMs = minCommandSpacingMs,
            requireReady = requireReady
        )
    }

    private suspend fun sendKsEncryptedCommandVariants(command: String, requireReady: Boolean = true): Boolean {
        val writeChars = availableKsEncryptedWriteChars()
        if (writeChars.isEmpty()) return false

        val payloads = KingsmithEncryptedProtocol.commandPayloads(command)
        var anySent = false
        for (writeChar in writeChars) {
            for (payload in payloads) {
                if (sendKsEncryptedCommand(writeChar, payload, requireReady = requireReady)) {
                    anySent = true
                }
            }
        }
        return anySent
    }

    private suspend fun awaitProtocolReady(protocol: WalkingPadProtocol): Boolean {
        val readyFlow = when (protocol) {
            WalkingPadProtocol.LegacyKingsmith -> legacyProtocolReady
            WalkingPadProtocol.KingsmithEncrypted -> ksEncryptedProtocolReady
            WalkingPadProtocol.FitnessMachineService -> ftmsProtocolReady
        }
        return readyFlow.value ||
            withTimeoutOrNull(PROTOCOL_READY_TIMEOUT_MS) {
                readyFlow.filter { it }.first()
            } == true
    }

    private suspend fun awaitStatusApplied(
        timeoutMillis: Long,
        isApplied: (TreadmillStatus) -> Boolean
    ): Boolean =
        isApplied(status.value) ||
            withTimeoutOrNull(timeoutMillis) {
                status.filter { isApplied(it) }.first()
            } != null

    private suspend fun sendControlCommand(
        label: String,
        cmd: ByteArray,
        protocol: WalkingPadProtocol,
        minCommandSpacingMs: Long = MIN_COMMAND_SPACING_MS,
        isApplied: (TreadmillStatus) -> Boolean
    ): Boolean {
        val previousStatusAt = lastStatusReceivedElapsedMillis
        val sent = sendCommand(cmd, protocol, minCommandSpacingMs)
        if (sent) {
            val commandHex = if (protocol == WalkingPadProtocol.FitnessMachineService) {
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
                        commandFailureDetail(protocol, commandHex, latestStatus)
                    )
                }
            }
        }
        return sent
    }

    private suspend fun sendKsEncryptedControlCommand(
        label: String,
        command: String,
        minCommandSpacingMs: Long = MIN_COMMAND_SPACING_MS,
        isApplied: (TreadmillStatus) -> Boolean
    ): Boolean {
        val previousStatusAt = lastStatusReceivedElapsedMillis
        val sent = sendKsEncryptedTextCommand(command, minCommandSpacingMs)
        if (sent) {
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
                        commandFailureDetail(WalkingPadProtocol.KingsmithEncrypted, command, latestStatus)
                    )
                }
            }
        }
        return sent
    }

    private fun commandFailureDetail(
        protocol: WalkingPadProtocol,
        commandHex: String,
        status: TreadmillStatus
    ): String =
        buildString {
            append("protocol=")
            append(protocol)
            append(" ")
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

    private fun isBeltStopped(status: TreadmillStatus): Boolean =
        status.state == TreadmillState.STOPPED || status.speed <= SPEED_APPLIED_TOLERANCE_KMH

    private fun availableKsEncryptedWriteChars(): List<String> =
        KingsmithEncryptedProtocol.characteristicPairs.map { it.writeCharSubstring }.filter { writeChar ->
            connection?.hasCharacteristicUuidSubstring(writeChar) == true
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
        return sendCommand(command, WalkingPadProtocol.LegacyKingsmith)
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
        val notificationsEnabled = connection?.enableNotifications(SERVICE_UUID, NOTIFY_CHAR_UUID) ?: false
        Timber.d("WalkingPad legacy notifications enabled: $notificationsEnabled")
        if (notificationsEnabled) {
            scope.launch {
                delay(PROTOCOL_READY_DELAY_MS)
                legacyProtocolReady.value = true
                _supportsNativeAutoMode.value = true
                markAnyProtocolReady()
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

    private fun activateKsEncryptedProtocolIfAvailable() {
        val pair = KingsmithEncryptedProtocol.characteristicPairs.firstOrNull { candidate ->
            connection?.hasCharacteristicUuidSubstring(candidate.readCharSubstring) == true &&
                connection?.hasCharacteristicUuidSubstring(candidate.writeCharSubstring) == true
        } ?: return

        activeKsEncryptedReadCharSubstring = pair.readCharSubstring
        activeKsEncryptedWriteCharSubstring = pair.writeCharSubstring
        val updatesEnabled = connection?.enableUpdatesByUuidSubstring(pair.readCharSubstring) ?: false
        Timber.d(
            "WalkingPad encrypted KS updates enabled: $updatesEnabled " +
                "read~=${pair.readCharSubstring} write~=${pair.writeCharSubstring}"
        )
        if (!updatesEnabled) {
            errorReporter.report(
                "WalkingPad Bluetooth",
                "Encrypted KS updates were not enabled",
                "read~=${pair.readCharSubstring} write~=${pair.writeCharSubstring}"
            )
            return
        }

        scope.launch {
            delay(GATT_DESCRIPTOR_WRITE_DELAY_MS)
            ksEncryptedHandshakeIndex = 0
            sendKsEncryptedHandshakeCommand()
        }
    }

    private fun activateFtmsProtocol(address: String) {
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
                ftmsProtocolReady.value = true
                markAnyProtocolReady()
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
        sendCommand(
            cmd = byteArrayOf(FTMS_OP_REQUEST_CONTROL),
            protocol = WalkingPadProtocol.FitnessMachineService
        ).also { sent ->
            if (sent) {
                ftmsControlRequested = true
            }
        }

    private suspend fun ensureFtmsControlRequested(): Boolean =
        ftmsControlRequested || requestFtmsControl()

    private fun markAnyProtocolReady() {
        protocolReady.value = true
        protocolReadyWatchdogJob?.cancel()
        _connectionState.value = ConnectionState.CONNECTED
    }

    private fun resetProtocolState() {
        protocolReady.value = false
        legacyProtocolReady.value = false
        ksEncryptedProtocolReady.value = false
        ftmsProtocolReady.value = false
        ftmsControlRequested = false
        resetKsEncryptedState()
    }

    private fun resetKsEncryptedState() {
        ksEncryptedHandshakeIndex = 0
        ksEncryptedReadBuffer = ByteArray(0)
        activeKsEncryptedTable = null
        candidateKsEncryptedTables = KingsmithEncryptedProtocol.encryptionTables.toSet()
        activeKsEncryptedReadCharSubstring = null
        activeKsEncryptedWriteCharSubstring = null
    }

    private suspend fun sendKsEncryptedHandshakeCommand(): Boolean {
        val command = KingsmithEncryptedProtocol.handshakeCommand(ksEncryptedHandshakeIndex) ?: return false
        return sendKsEncryptedTextCommand(
            command = command,
            minCommandSpacingMs = KS_ENCRYPTED_HANDSHAKE_SPACING_MS,
            requireReady = false
        )
    }

    private fun isKsEncryptedReadCharacteristic(uuid: UUID): Boolean {
        val activeRead = activeKsEncryptedReadCharSubstring
        val lowerUuid = uuid.toString().lowercase(Locale.US)
        if (activeRead != null) {
            return lowerUuid.contains(activeRead.lowercase(Locale.US))
        }
        return KingsmithEncryptedProtocol.characteristicPairs.any { pair ->
            lowerUuid.contains(pair.readCharSubstring.lowercase(Locale.US))
        }
    }

    private fun handleKsEncryptedNotification(data: ByteArray) {
        lastRawNotificationHex = data.toHexString()
        ksEncryptedReadBuffer += data
        if (ksEncryptedReadBuffer.lastOrNull() != KingsmithEncryptedProtocol.TERMINATOR) return

        val packet = ksEncryptedReadBuffer
        ksEncryptedReadBuffer = ByteArray(0)
        val decodeTables = activeKsEncryptedTable?.let { setOf(it) } ?: candidateKsEncryptedTables
        val candidates = KingsmithEncryptedProtocol.decodeCandidates(packet, decodeTables)
            .ifEmpty { KingsmithEncryptedProtocol.decodeCandidates(packet) }
        val decoded = selectKsEncryptedDecodedText(candidates) ?: run {
            Timber.w("Unable to decode encrypted KS notification: ${packet.toHexString()}")
            return
        }
        val matchingCandidates = candidates.filter { candidate ->
            if (ksEncryptedProtocolReady.value) {
                KingsmithEncryptedProtocol.parseProps(candidate.text) != null
            } else {
                KingsmithEncryptedProtocol.parseProps(candidate.text) != null ||
                    KingsmithEncryptedProtocol.handshakeResponseMatches(ksEncryptedHandshakeIndex, candidate.text)
            }
        }
        if (matchingCandidates.isNotEmpty()) {
            candidateKsEncryptedTables = matchingCandidates.map { it.table }.toSet()
            if (candidateKsEncryptedTables.size == 1) {
                activeKsEncryptedTable = candidateKsEncryptedTables.first()
            }
        }
        Timber.d("Decoded encrypted KS notification: ${decoded.text}")

        val props = KingsmithEncryptedProtocol.parseProps(decoded.text)
        if (props != null) {
            lastStatusReceivedElapsedMillis = SystemClock.elapsedRealtime()
            _status.value = KingsmithEncryptedProtocol.applyProps(status.value, props)
            if (!ksEncryptedProtocolReady.value) {
                ksEncryptedProtocolReady.value = true
                _supportsNativeAutoMode.value = true
                markAnyProtocolReady()
                startStatusPolling()
            }
            return
        }

        handleKsEncryptedHandshakeText(decoded.text)
    }

    private fun selectKsEncryptedDecodedText(
        candidates: List<KingsmithEncryptedProtocol.DecodedText>
    ): KingsmithEncryptedProtocol.DecodedText? {
        if (candidates.isEmpty()) return null
        return if (ksEncryptedProtocolReady.value) {
            candidates.firstOrNull { KingsmithEncryptedProtocol.parseProps(it.text) != null } ?: candidates.first()
        } else {
            candidates.firstOrNull {
                KingsmithEncryptedProtocol.handshakeResponseMatches(ksEncryptedHandshakeIndex, it.text)
            } ?: candidates.firstOrNull { KingsmithEncryptedProtocol.parseProps(it.text) != null } ?: candidates.first()
        }
    }

    private fun handleKsEncryptedHandshakeText(text: String) {
        if (ksEncryptedProtocolReady.value) return
        if (!KingsmithEncryptedProtocol.handshakeResponseMatches(ksEncryptedHandshakeIndex, text)) {
            errorReporter.report(
                "WalkingPad Bluetooth",
                "Encrypted KS handshake response was unexpected",
                "step=$ksEncryptedHandshakeIndex response=$text"
            )
            return
        }

        ksEncryptedHandshakeIndex += 1
        if (ksEncryptedHandshakeIndex > KS_ENCRYPTED_LAST_HANDSHAKE_INDEX) {
            ksEncryptedProtocolReady.value = true
            _supportsNativeAutoMode.value = true
            markAnyProtocolReady()
            startStatusPolling()
            Timber.d("WalkingPad encrypted KS protocol ready")
            return
        }

        scope.launch {
            sendKsEncryptedHandshakeCommand()
        }
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
        val calories = if (flags and FTMS_TREADMILL_ENERGY_FLAG != 0 && data.size >= offset + 5) {
            val totalEnergyKcal = readUInt16LE(data, offset)
            offset += 5
            totalEnergyKcal
        } else {
            if (flags and FTMS_TREADMILL_ENERGY_FLAG != 0) offset += 5
            status.value.calories
        }
        if (flags and FTMS_TREADMILL_HEART_RATE_FLAG != 0) offset += 1
        if (flags and FTMS_TREADMILL_METABOLIC_EQUIVALENT_FLAG != 0) offset += 1

        val elapsedTime = if (flags and FTMS_TREADMILL_ELAPSED_TIME_FLAG != 0 && data.size >= offset + 2) {
            readUInt16LE(data, offset)
        } else {
            status.value.time
        }

        if (ksEncryptedProtocolReady.value && status.value.hasStepCount) {
            _status.value = status.value.copy(calories = calories)
            return
        }

        _status.value = status.value.copy(
            state = if (speed > SPEED_APPLIED_TOLERANCE_KMH) TreadmillState.ACTIVE else TreadmillState.STOPPED,
            speed = speed,
            mode = if (legacyProtocolReady.value || ksEncryptedProtocolReady.value) {
                status.value.mode
            } else {
                TreadmillMode.MANUAL
            },
            time = elapsedTime,
            distance = distance,
            calories = calories
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
            hasStepCount = true,
            calories = _status.value.calories,
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
        private const val MIN_COMMAND_SPACING_MS = 250L
        private const val SPEED_COMMAND_SPACING_MS = 100L
        private const val STATUS_POLL_INTERVAL_MS = 750L
        private const val COMMAND_REPLY_TIMEOUT_MS = 2_500L
        private const val SPEED_APPLIED_TOLERANCE_KMH = 0.05f
        private const val PROTOCOL_READY_DELAY_MS = 500L
        private const val PROTOCOL_READY_TIMEOUT_MS = 5_000L
        private const val PROTOCOL_READY_WATCHDOG_MS = 8_000L
        private const val GATT_DESCRIPTOR_WRITE_DELAY_MS = 400L
        private const val FTMS_STOP_APPLY_TIMEOUT_MS = 2_000L
        private const val FTMS_BELT_COAST_STOP_TIMEOUT_MS = 15_000L
        private const val KS_ENCRYPTED_STOP_APPLY_TIMEOUT_MS = 1_200L
        private const val KS_ENCRYPTED_WRITE_CHUNK_SIZE = 16
        private const val KS_ENCRYPTED_WRITE_CHUNK_DELAY_MS = 120L
        private const val KS_ENCRYPTED_HANDSHAKE_SPACING_MS = 25L
        private const val FTMS_OP_REQUEST_CONTROL: Byte = 0x00
        private const val FTMS_OP_RESET: Byte = 0x01
        private const val FTMS_OP_SET_TARGET_SPEED: Byte = 0x02
        private const val FTMS_OP_START_OR_RESUME: Byte = 0x07
        private const val FTMS_OP_STOP_OR_PAUSE: Byte = 0x08
        private const val FTMS_OP_RESPONSE_CODE: Byte = 0x80.toByte()
        private const val FTMS_STOP: Byte = 0x01
        private const val FTMS_PAUSE: Byte = 0x02
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
        private const val KS_ENCRYPTED_LAST_HANDSHAKE_INDEX = 7
    }

    private enum class WalkingPadProtocol {
        LegacyKingsmith,
        KingsmithEncrypted,
        FitnessMachineService
    }
}
