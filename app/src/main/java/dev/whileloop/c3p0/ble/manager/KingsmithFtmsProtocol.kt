package dev.whileloop.c3p0.ble.manager

import dev.whileloop.c3p0.ble.model.TreadmillMode
import dev.whileloop.c3p0.ble.model.TreadmillState
import dev.whileloop.c3p0.ble.model.TreadmillStatus
import java.util.UUID

internal object KingsmithFtmsProtocol {
    data class NoLoadStopResponse(
        val enabled: Boolean,
        val timeoutSeconds: Int?
    )

    val SERVICE_UUID: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
    const val TREADMILL_DATA_CHAR = "00002acd"
    const val TRAINING_STATUS_CHAR = "00002ad3"
    const val CONTROL_POINT_CHAR = "00002ad9"
    const val FITNESS_MACHINE_STATUS_CHAR = "00002ada"

    const val ODM_WRITE_CHAR = "d18d2c10"
    val supplementNotifyCharSubstrings = listOf(
        "c5330b00fdf7",
        "c5330e00fdf7"
    )
    val supplementWriteCharSubstrings = listOf(
        "d18d2c10",
        "c5330d00fdf7",
        "c5330f00fdf7"
    )

    val PROPERTY_LIST_PREAMBLE = byteArrayOf(
        0x01, 0x00, 0x0d, 0x00, 0x06, 0x0b, 0x0f, 0x0d
    )

    val REQUEST_CONTROL_COMMAND = byteArrayOf(0x00)
    val START_OR_RESUME_COMMAND = byteArrayOf(0x07)
    val PAUSE_COMMAND = byteArrayOf(0x08, 0x02)
    val STOP_COMMAND = byteArrayOf(0x08, 0x01)

    private const val SUPPLEMENT_HEADER_0 = 0x01
    private const val SUPPLEMENT_HEADER_1 = 0x00
    private const val SUPPLEMENT_SET_PROPERTY = 0x21
    private const val SUPPLEMENT_PROPERTY_AUTO_STOP = 0x02

    fun setTargetSpeedCommand(speedKmh: Float): ByteArray {
        val raw = (speedKmh * 100).toInt().coerceIn(0, 0xFFFF)
        return byteArrayOf(
            0x02,
            (raw and 0xFF).toByte(),
            ((raw shr 8) and 0xFF).toByte()
        )
    }

    fun noLoadStopCommands(enabled: Boolean, timeoutSeconds: Int): List<ByteArray> {
        val value = if (enabled) 1 else 0
        return listOf(
            supplementPropertyCommand(
                propertyId = SUPPLEMENT_PROPERTY_AUTO_STOP,
                value = value
            )
        )
    }

    private fun supplementPropertyCommand(propertyId: Int, value: Int): ByteArray =
        wrapSupplementCommand(
            byteArrayOf(
                SUPPLEMENT_SET_PROPERTY.toByte(),
                propertyId.toByte(),
                value.toByte()
            )
        )

    private fun wrapSupplementCommand(body: ByteArray): ByteArray {
        val checksum = body.fold(0) { sum, byte -> sum + (byte.toInt() and 0xFF) } and 0xFF
        return byteArrayOf(
            SUPPLEMENT_HEADER_0.toByte(),
            SUPPLEMENT_HEADER_1.toByte(),
            (body.size + 1).toByte(),
            0x00
        ) + body + checksum.toByte()
    }

    fun parseNoLoadStopResponse(data: ByteArray): NoLoadStopResponse? {
        if (data.size < 6) return null
        if (data.first() != 0x72.toByte() || data.last() != 0xF7.toByte()) return null
        val propertyIndex = (3 until data.lastIndex).firstOrNull { index ->
            (data[index].toInt() and 0xFF) == SUPPLEMENT_PROPERTY_AUTO_STOP
        } ?: return null
        val value = data.getOrNull(propertyIndex + 1)?.toInt()?.and(0xFF) ?: return null
        return NoLoadStopResponse(
            enabled = value > 0,
            timeoutSeconds = null
        )
    }

    fun parseTreadmillData(data: ByteArray, current: TreadmillStatus): TreadmillStatus? {
        if (data.size < 4) return null

        val flags = readUInt16Le(data, 0)
        var offset = 2
        if (offset + 2 > data.size) return null

        val speed = readUInt16Le(data, offset) / 100f
        offset += 2

        if (flags.hasFlag(1)) offset += 2

        var distance = current.distance
        if (flags.hasFlag(2)) {
            if (offset + 3 > data.size) return null
            distance = readUInt24Le(data, offset) / 10
            offset += 3
        }

        if (flags.hasFlag(3)) offset += 4
        if (flags.hasFlag(4)) offset += 4
        if (flags.hasFlag(5)) offset += 1
        if (flags.hasFlag(6)) offset += 1

        var calories = current.calories
        if (flags.hasFlag(7)) {
            if (offset + 5 > data.size) return null
            calories = readUInt16Le(data, offset)
            offset += 5
        }

        if (flags.hasFlag(8)) offset += 1
        if (flags.hasFlag(9)) offset += 1

        var elapsed = current.time
        if (flags.hasFlag(10)) {
            if (offset + 2 > data.size) return null
            elapsed = readUInt16Le(data, offset)
            offset += 2
        }

        if (flags.hasFlag(11)) offset += 2
        if (flags.hasFlag(12)) offset += 4

        var steps = current.steps
        var hasSteps = current.hasStepCount
        if (flags.hasFlag(13)) {
            if (offset + 3 > data.size) return null
            steps = readUInt16Le(data, offset)
            hasSteps = true
        }

        val state = when {
            speed > 0.05f -> TreadmillState.ACTIVE
            current.state == TreadmillState.STARTING -> TreadmillState.STARTING
            else -> current.state
        }

        return current.copy(
            state = state,
            speed = speed,
            distance = distance,
            steps = steps,
            hasStepCount = hasSteps,
            calories = calories,
            time = elapsed
        )
    }

    fun applyFitnessMachineStatus(data: ByteArray, current: TreadmillStatus): TreadmillStatus {
        if (data.isEmpty()) return current
        return when (data[0].toInt() and 0xFF) {
            0x02 -> {
                val stopOrPause = data.getOrNull(1)?.toInt()?.and(0xFF)
                current.copy(state = if (stopOrPause == 1) TreadmillState.STOPPED else TreadmillState.STANDBY)
            }
            0x03 -> current.copy(state = TreadmillState.STOPPED)
            0x04 -> current.copy(state = TreadmillState.ACTIVE)
            0x05 -> {
                if (data.size >= 3) current.copy(speed = readUInt16Le(data, 1) / 100f) else current
            }
            else -> current
        }
    }

    fun applyTrainingStatus(data: ByteArray, current: TreadmillStatus): TreadmillStatus {
        if (data.isEmpty()) return current
        val state = when (data[0].toInt() and 0xFF) {
            0x01 -> TreadmillState.ACTIVE
            0x02 -> TreadmillState.STANDBY
            else -> current.state
        }
        val mode = when (data.getOrNull(1)?.toInt()?.and(0xFF)) {
            0x00 -> TreadmillMode.MANUAL
            0x01 -> TreadmillMode.AUTO
            else -> current.mode
        }
        return current.copy(state = state, mode = mode)
    }

    private fun Int.hasFlag(bit: Int): Boolean =
        this and (1 shl bit) != 0

    private fun readUInt16Le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt24Le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
}
