package dev.whileloop.c3p0.ble.manager

import dev.whileloop.c3p0.ble.model.TreadmillMode
import dev.whileloop.c3p0.ble.model.TreadmillState
import dev.whileloop.c3p0.ble.model.TreadmillStatus
import org.junit.Assert.assertArrayEquals
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
    fun pollPropsRequestsOfficialMotionAndStateFields() {
        assertEquals(
            "servers getProp 1 2 3 4 5 7 9 12 13 16 17 23 24 31",
            KingsmithEncryptedProtocol.POLL_PROPS_COMMAND
        )
    }

    @Test
    fun applyPropsReadsWalkingPadTelemetry() {
        val props = KingsmithEncryptedProtocol.parseProps(
            "props runState 1 CurrentSpeed 3.2 ControlMode 1 RunningTotalTime 42 " +
                "RunningDistance 120 RunningSteps 345 BurnCalories 17.8"
        )

        val status = KingsmithEncryptedProtocol.applyProps(TreadmillStatus(), props.orEmpty())

        assertEquals(TreadmillState.ACTIVE, status.state)
        assertEquals(3.2f, status.speed, 0.0001f)
        assertEquals(TreadmillMode.MANUAL, status.mode)
        assertEquals(42, status.time)
        assertEquals(12, status.distance)
        assertEquals(345, status.steps)
        assertEquals(17, status.calories)
        assertTrue(status.hasStepCount)
    }

    @Test
    fun aisFramesWrapPayloadWithCommandHeader() {
        val payload = "props runState 1".toByteArray()

        val frames = KingsmithEncryptedProtocol.aisCommandFrames(
            payload = payload,
            startMessageId = 14,
            maxPayloadBytes = 8
        )

        assertEquals(2, frames.size)
        assertEquals(14, frames[0][0].toInt() and 0x0F)
        assertEquals(2, frames[0][1].toInt())
        assertEquals(0x10, frames[0][2].toInt() and 0xFF)
        assertEquals(8, frames[0][3].toInt())
        assertEquals(15, frames[1][0].toInt() and 0x0F)
        assertEquals(2, frames[1][1].toInt())
        assertEquals(0x11, frames[1][2].toInt() and 0xFF)
        assertArrayEquals(payload.copyOfRange(8, payload.size), frames[1].copyOfRange(4, frames[1].size))
    }

    @Test
    fun aisParserReadsConcatenatedPackets() {
        val first = KingsmithEncryptedProtocol.aisCommandFrames(
            payload = byteArrayOf(1, 2, 3),
            startMessageId = 1
        ).single()
        val second = KingsmithEncryptedProtocol.aisCommandFrames(
            payload = byteArrayOf(4, 5),
            startMessageId = 2
        ).single()

        val packets = KingsmithEncryptedProtocol.parseAisPackets(first + second)

        assertEquals(2, packets.size)
        assertEquals(2, packets[0].commandType.toInt())
        assertArrayEquals(byteArrayOf(1, 2, 3), packets[0].payload)
        assertArrayEquals(byteArrayOf(4, 5), packets[1].payload)
    }
}
