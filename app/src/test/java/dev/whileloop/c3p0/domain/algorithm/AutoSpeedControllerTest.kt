package dev.whileloop.c3p0.domain.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSpeedControllerTest {
    @Test
    fun belowZoneUsesRecentZone2SpeedAsFloor() {
        val adjustments = mutableListOf<Float>()
        val controller = AutoSpeedController(
            targetHr = 123,
            zoneMinHr = 114,
            zoneMaxHr = 133,
            adjustmentIntervalSeconds = 30,
            maxAdjustment = 0.5f
        ).apply {
            onSpeedAdjustmentRequired = { adjustment -> adjustments += adjustment }
        }

        controller.addHrSample(hr = 123, speedKmh = 4.8f, timestamp = 100)
        controller.addHrSample(hr = 124, speedKmh = 4.8f, timestamp = 30_100)
        controller.addHrSample(hr = 105, speedKmh = 4.8f, timestamp = 30_200)
        controller.addHrSample(hr = 106, speedKmh = 4.8f, timestamp = 60_200)

        assertEquals(1, adjustments.size)
        assertTrue(adjustments.first() > 0.19f)
    }

    @Test
    fun inZoneDoesNotAdjustSpeed() {
        val adjustments = mutableListOf<Float>()
        val controller = AutoSpeedController(
            targetHr = 123,
            zoneMinHr = 114,
            zoneMaxHr = 133,
            adjustmentIntervalSeconds = 30
        ).apply {
            onSpeedAdjustmentRequired = { adjustment -> adjustments += adjustment }
        }

        controller.addHrSample(hr = 120, speedKmh = 4.0f, timestamp = 100)
        controller.addHrSample(hr = 126, speedKmh = 4.0f, timestamp = 30_100)

        assertTrue(adjustments.isEmpty())
    }
}
