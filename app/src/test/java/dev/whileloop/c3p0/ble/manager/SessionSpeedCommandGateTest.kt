package dev.whileloop.c3p0.ble.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSpeedCommandGateTest {
    @Test
    fun blocksSpeedCommandsByDefaultAndAfterPauseOrStop() {
        val gate = SessionSpeedCommandGate()

        assertFalse(gate.isAllowed())
        gate.allow()
        assertTrue(gate.isAllowed())
        gate.block()
        assertFalse(gate.isAllowed())
    }
}
