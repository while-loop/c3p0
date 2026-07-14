package dev.whileloop.c3p0.ble.manager

import dev.whileloop.c3p0.ble.model.TreadmillState
import dev.whileloop.c3p0.ble.model.TreadmillStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KingsmithFtmsProtocolTest {
    @Test
    fun setTargetSpeedUsesFtmsHundredthsLittleEndian() {
        assertArrayEquals(
            byteArrayOf(0x02, 0x90.toByte(), 0x01),
            KingsmithFtmsProtocol.setTargetSpeedCommand(4.0f)
        )
    }

    @Test
    fun setTargetSpeedRoundsToNearestHundredth() {
        assertArrayEquals(
            byteArrayOf(0x02, 0x91.toByte(), 0x01),
            KingsmithFtmsProtocol.setTargetSpeedCommand(4.005f)
        )
    }

    @Test
    fun stopAndPauseUseDistinctFtmsParams() {
        assertArrayEquals(byteArrayOf(0x08, 0x01), KingsmithFtmsProtocol.STOP_COMMAND)
        assertArrayEquals(byteArrayOf(0x08, 0x02), KingsmithFtmsProtocol.PAUSE_COMMAND)
    }

    @Test
    fun treadmillDataParsesKingSmithStepExtension() {
        val flags = 0x2404
        val data = byteArrayOf(
            (flags and 0xFF).toByte(),
            ((flags shr 8) and 0xFF).toByte(),
            0x40,
            0x01,
            0x7b,
            0x00,
            0x00,
            0x58,
            0x02,
            0x39,
            0x30,
            0x00
        )

        val status = KingsmithFtmsProtocol.parseTreadmillData(data, TreadmillStatus())

        checkNotNull(status)
        assertEquals(TreadmillState.ACTIVE, status.state)
        assertEquals(3.2f, status.speed, 0.001f)
        assertEquals(12, status.distance)
        assertEquals(600, status.time)
        assertEquals(12345, status.steps)
        assertTrue(status.hasStepCount)
    }
}
