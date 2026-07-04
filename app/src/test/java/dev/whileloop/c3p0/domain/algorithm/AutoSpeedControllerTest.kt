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

    @Test
    fun lowerZoneEdgeTrendingDownNudgesSpeedUp() {
        val adjustments = mutableListOf<Float>()
        val controller = edgeGuardController(adjustments)

        controller.feedDecisionWindow(hr = 116, speedKmh = 4.0f, timestamp = 100_000)
        controller.feedDecisionWindow(hr = 115, speedKmh = 4.0f, timestamp = 101_000)
        controller.feedDecisionWindow(hr = 114, speedKmh = 4.0f, timestamp = 102_000)

        assertEquals(1, adjustments.size)
        assertEquals(0.1f * KM_PER_MILE, adjustments.first(), 0.0001f)
    }

    @Test
    fun lowerZoneEdgeTrendingUpDoesNotAdjust() {
        val adjustments = mutableListOf<Float>()
        val controller = edgeGuardController(adjustments)

        controller.feedDecisionWindow(hr = 114, speedKmh = 4.0f, timestamp = 100_000)
        controller.feedDecisionWindow(hr = 115, speedKmh = 4.0f, timestamp = 101_000)
        controller.feedDecisionWindow(hr = 116, speedKmh = 4.0f, timestamp = 102_000)

        assertTrue(adjustments.isEmpty())
    }

    @Test
    fun upperZoneEdgeTrendingUpNudgesSpeedDown() {
        val adjustments = mutableListOf<Float>()
        val controller = edgeGuardController(adjustments)

        controller.feedDecisionWindow(hr = 131, speedKmh = 4.0f, timestamp = 100_000)
        controller.feedDecisionWindow(hr = 132, speedKmh = 4.0f, timestamp = 101_000)
        controller.feedDecisionWindow(hr = 133, speedKmh = 4.0f, timestamp = 102_000)

        assertEquals(1, adjustments.size)
        assertEquals(-0.1f * KM_PER_MILE, adjustments.first(), 0.0001f)
    }

    @Test
    fun upperZoneEdgeTrendingDownDoesNotAdjust() {
        val adjustments = mutableListOf<Float>()
        val controller = edgeGuardController(adjustments)

        controller.feedDecisionWindow(hr = 133, speedKmh = 4.0f, timestamp = 100_000)
        controller.feedDecisionWindow(hr = 132, speedKmh = 4.0f, timestamp = 101_000)
        controller.feedDecisionWindow(hr = 131, speedKmh = 4.0f, timestamp = 102_000)

        assertTrue(adjustments.isEmpty())
    }

    private fun edgeGuardController(adjustments: MutableList<Float>): AutoSpeedController =
        AutoSpeedController(
            targetHr = 123,
            zoneMinHr = 114,
            zoneMaxHr = 133,
            adjustmentIntervalSeconds = 1
        ).apply {
            onSpeedAdjustmentRequired = { adjustment -> adjustments += adjustment }
        }

    private fun AutoSpeedController.feedDecisionWindow(hr: Int, speedKmh: Float, timestamp: Long) {
        if (timestamp == 100_000L) {
            addHrSample(hr = hr, speedKmh = speedKmh, timestamp = timestamp - 1_000)
        }
        addHrSample(hr = hr, speedKmh = speedKmh, timestamp = timestamp)
    }

    private companion object {
        private const val KM_PER_MILE = 1.60934f
    }
}
