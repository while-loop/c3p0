package dev.whileloop.c3p0.ble.manager

import dev.whileloop.c3p0.ble.model.TreadmillMode
import dev.whileloop.c3p0.ble.model.TreadmillState
import dev.whileloop.c3p0.ble.model.TreadmillStatus
import java.util.Base64
import java.util.Locale

internal object KingsmithEncryptedProtocol {
    const val TERMINATOR: Byte = 13
    const val START_COMMAND = "props runState 1"
    const val STOP_COMMAND = "props runState 0"
    const val AUTO_MODE_COMMAND = "props ControlMode 0"
    const val MANUAL_MODE_COMMAND = "props ControlMode 1"
    const val STANDBY_MODE_COMMAND = "props ControlMode 2"
    const val POLL_PROPS_COMMAND = "servers getProp 1 2 3 4 5 7 9 12 13 16 17 23 24 31"
    const val NO_LOAD_STOP_SWITCH_KEY = "AuToStop"
    const val NO_LOAD_STOP_TIMEOUT_KEY = "NoloadStop"

    val characteristicPairs = listOf(
        CharacteristicPair(readCharSubstring = "0000fed8", writeCharSubstring = "0000fed7"),
        CharacteristicPair(readCharSubstring = "0001fed7", writeCharSubstring = "0001fed8"),
        CharacteristicPair(readCharSubstring = "0002fed8", writeCharSubstring = "0002fed7"),
        CharacteristicPair(readCharSubstring = "c5330e00fdf7", writeCharSubstring = "c5330f00fdf7"),
        CharacteristicPair(readCharSubstring = "5833ff02", writeCharSubstring = "5833ff03")
    )

    val encryptionTables = listOf(
        "SaCw4FGHIJqLhN+P9RVTU/WcY6ObDdefgEijklmnopQrsBuvMxXz1yA2t5078KZ3=",
        "ZaCw4FGHIJqLhN+P9RMTU/WcY6ObDdefgEijklmnopQrsBuvVxXz1yA2t5078KS3=",
        "0aCw4FGHIJqLhN+P9RVTU/WcY6ObDdefgEijklmnopQrsBuvMxXz1yA2t5Z78KS3=",
        "ZaCw4FGHIJqLhN9P+RVTU/WcY6ObDdefgEijklmnopQrsBuvMxXz1yA2t5078KS3=",
        "iaCw4FGHIJqLhN+P9RVTU/WcY6ObDdefgEZjklmnopQrsBuvMxXz1yA2t5078KS3=",
        "ZaCw4FGHIJqLhN+P8RVTU/WcY6ObDdefgEijklmnopQrsBuvMxXz1yA2t5079KS3=",
        "baCw4FGHIJqLhN+P9RVTU/WcY6OZDdefgEijklmnopQrsBuvMxXz1yA2t5078KS3="
    )

    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="

    fun handshakeCommand(index: Int): String? =
        when (index) {
            0 -> ""
            1 -> "shake"
            2 -> "net"
            3 -> "get_dn"
            4 -> "get_pk"
            5 -> "time_posix ${System.currentTimeMillis() / 1000L}"
            6 -> "version"
            7 -> "servers getProp 1 2 7 12 23 24 31"
            else -> null
        }

    fun handshakeResponseMatches(index: Int, text: String): Boolean {
        val expected = when (index) {
            0 -> "format error"
            1 -> "shake"
            2 -> "net"
            3 -> "get_dn"
            4 -> "get_pk"
            5 -> "time_posix"
            6 -> "version"
            7 -> "servers"
            else -> return false
        }
        return text.contains(expected, ignoreCase = true)
    }

    fun speedCommand(speedKmh: Float): String =
        "props CurrentSpeed %.1f".format(Locale.US, speedKmh)

    fun encode(command: String, table: String): ByteArray {
        val base64 = Base64.getEncoder().encode(command.toByteArray(Charsets.UTF_8))
        val encoded = ByteArray(base64.size + 1)
        base64.forEachIndexed { index, byte ->
            val alphabetIndex = BASE64_ALPHABET.indexOf(byte.toInt().toChar())
            encoded[index] = if (alphabetIndex >= 0) table[alphabetIndex].code.toByte() else '_'.code.toByte()
        }
        encoded[encoded.lastIndex] = TERMINATOR
        return encoded
    }

    fun commandPayloads(command: String): List<ByteArray> =
        encryptionTables
            .map { table -> encode(command, table) }
            .distinctBy { payload -> payload.contentToString() }

    fun decodeAny(data: ByteArray, preferredTable: String? = null): DecodedText? {
        val tables = buildList {
            if (preferredTable != null) add(preferredTable)
            addAll(encryptionTables.filter { it != preferredTable })
        }
        return decodeCandidates(data, tables)
            .firstOrNull()
    }

    fun decodeCandidates(data: ByteArray, tables: Collection<String> = encryptionTables): List<DecodedText> =
        tables
            .asSequence()
            .mapNotNull { table -> decode(data, table)?.let { DecodedText(table, it) } }
            .filter { (_, text) -> isPlausibleText(text) }
            .toList()

    fun decode(data: ByteArray, table: String): String? {
        val packet = if (data.lastOrNull() == TERMINATOR) data.copyOf(data.size - 1) else data
        val base64 = ByteArray(packet.size)
        packet.forEachIndexed { index, byte ->
            val tableIndex = table.indexOf(byte.toInt().toChar())
            if (tableIndex < 0) return null
            base64[index] = BASE64_ALPHABET[tableIndex].code.toByte()
        }
        return runCatching {
            String(Base64.getDecoder().decode(base64), Charsets.UTF_8)
        }.getOrNull()
    }

    fun parseProps(text: String): Map<String, String>? {
        if (!text.startsWith("props", ignoreCase = true)) return null
        val parts = text.removePrefix("props").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size < 2) return emptyMap()
        return buildMap {
            var index = 0
            while (index < parts.lastIndex) {
                put(parts[index], parts[index + 1].trim('"'))
                index += 2
            }
        }
    }

    fun applyProps(current: TreadmillStatus, props: Map<String, String>): TreadmillStatus {
        val rawState = props["runState"]?.toIntOrNull()
        val rawMode = props["ControlMode"]?.toIntOrNull()
        val runningDistanceMeters = props["RunningDistance"]?.toIntOrNull()
        val runningSteps = props["RunningSteps"]?.toIntOrNull()

        return current.copy(
            state = rawState?.toTreadmillState() ?: current.state,
            speed = props["CurrentSpeed"]?.toFloatOrNull() ?: current.speed,
            mode = rawMode?.toTreadmillMode() ?: current.mode,
            time = props["RunningTotalTime"]?.toIntOrNull() ?: current.time,
            distance = runningDistanceMeters?.let { meters -> meters / 10 } ?: current.distance,
            steps = runningSteps ?: current.steps,
            calories = props["BurnCalories"]?.toFloatOrNull()?.toInt() ?: current.calories,
            hasStepCount = runningSteps != null || current.hasStepCount,
            noLoadStopEnabled = props[NO_LOAD_STOP_SWITCH_KEY]?.toBooleanInt() ?: current.noLoadStopEnabled,
            noLoadStopTimeoutSeconds =
                props[NO_LOAD_STOP_TIMEOUT_KEY]?.toIntOrNull() ?: current.noLoadStopTimeoutSeconds
        )
    }

    fun noLoadStopSwitchCommand(enabled: Boolean): String =
        "props $NO_LOAD_STOP_SWITCH_KEY ${if (enabled) 1 else 0}"

    fun noLoadStopTimeoutCommand(timeoutSeconds: Int): String =
        "props $NO_LOAD_STOP_TIMEOUT_KEY $timeoutSeconds"

    private fun isPlausibleText(text: String): Boolean {
        if (text.isBlank()) return true
        if (text.any { it.code < 9 || it.code > 126 }) return false
        return listOf(
            "format error",
            "shake",
            "net",
            "get_dn",
            "get_pk",
            "time_posix",
            "version",
            "servers",
            "props"
        ).any { token -> text.contains(token, ignoreCase = true) }
    }

    private fun Int.toTreadmillState(): TreadmillState =
        when (this) {
            0 -> TreadmillState.STOPPED
            1 -> TreadmillState.ACTIVE
            5 -> TreadmillState.STANDBY
            9 -> TreadmillState.STARTING
            else -> TreadmillState.ERROR
        }

    private fun Int.toTreadmillMode(): TreadmillMode? =
        when (this) {
            0 -> TreadmillMode.AUTO
            1 -> TreadmillMode.MANUAL
            2 -> TreadmillMode.STANDBY
            else -> null
        }

    private fun String.toBooleanInt(): Boolean? =
        when (toIntOrNull()) {
            0 -> false
            1 -> true
            else -> null
        }

    data class CharacteristicPair(
        val readCharSubstring: String,
        val writeCharSubstring: String
    )

    data class DecodedText(
        val table: String,
        val text: String
    )
}
