package dev.whileloop.c3p0.ble.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleErrorReporterTest {
    @Test
    fun `errors are suppressed while debug mode is disabled`() {
        val reporter = BleErrorReporter()

        reporter.report("Bluetooth", "Connection failed")

        assertTrue(reporter.errors.value.isEmpty())
    }

    @Test
    fun `enabling debug mode collects errors`() {
        val reporter = BleErrorReporter()
        reporter.setEnabled(true)

        reporter.report("Bluetooth", "Connection failed", "status=133")

        assertEquals(1, reporter.errors.value.size)
        assertEquals("status=133", reporter.errors.value.single().detail)
    }

    @Test
    fun `disabling debug mode clears collected errors`() {
        val reporter = BleErrorReporter()
        reporter.setEnabled(true)
        reporter.report("Bluetooth", "Connection failed")

        reporter.setEnabled(false)

        assertTrue(reporter.errors.value.isEmpty())
    }
}
