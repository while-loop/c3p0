package dev.whileloop.c3p0.ble.manager

import dev.whileloop.c3p0.ble.model.TreadmillMode
import dev.whileloop.c3p0.ble.model.TreadmillState
import dev.whileloop.c3p0.ble.model.TreadmillStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KingsmithEncryptedProtocolTest {
    @Test
    fun encodedCommandDecodesWithSameTable() {
        val table = KingsmithEncryptedProtocol.encryptionTables.first()
        val command = KingsmithEncryptedProtocol.speedCommand(3.2f)

        val encoded = KingsmithEncryptedProtocol.encode(command, table)

        assertEquals(KingsmithEncryptedProtocol.TERMINATOR, encoded.last())
        assertEquals(command, KingsmithEncryptedProtocol.decode(encoded, table))
    }

    @Test
    fun decodeAnyFindsMatchingTableForPlausiblePayload() {
        val table = KingsmithEncryptedProtocol.encryptionTables.last()
        val encoded = KingsmithEncryptedProtocol.encode(
            "props CurrentSpeed 3.2 RunningSteps 1234",
            table
        )

        val decoded = KingsmithEncryptedProtocol.decodeAny(encoded)
        val candidates = KingsmithEncryptedProtocol.decodeCandidates(encoded)

        assertTrue(decoded?.text?.startsWith("props CurrentSpeed") == true)
        assertTrue(candidates.any { it.table == table && it.text == "props CurrentSpeed 3.2 RunningSteps 1234" })
    }

    @Test
    fun applyPropsReadsRunningStepsAndDistance() {
        val props = KingsmithEncryptedProtocol.parseProps(
            "props runState 1 CurrentSpeed 3.2 ControlMode 1 RunningTotalTime 42 " +
                "RunningDistance 120 RunningSteps 345"
        )

        val status = KingsmithEncryptedProtocol.applyProps(TreadmillStatus(), props.orEmpty())

        assertEquals(TreadmillState.ACTIVE, status.state)
        assertEquals(3.2f, status.speed, 0.0001f)
        assertEquals(TreadmillMode.MANUAL, status.mode)
        assertEquals(42, status.time)
        assertEquals(12, status.distance)
        assertEquals(345, status.steps)
        assertTrue(status.hasStepCount)
    }
}
