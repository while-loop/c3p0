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
    fun belowZoneDoesNotRequestSpeedAboveConfiguredMax() {
        val adjustments = mutableListOf<Float>()
        val controller = AutoSpeedController(
            targetHr = 123,
            zoneMinHr = 114,
            zoneMaxHr = 133,
            maxSpeed = 3.5f * KM_PER_MILE,
            adjustmentIntervalSeconds = 30,
            maxAdjustment = 0.5f
        ).apply {
            onSpeedAdjustmentRequired = { adjustment -> adjustments += adjustment }
        }

        controller.addHrSample(hr = 100, speedKmh = 3.5f * KM_PER_MILE, timestamp = 100)
        controller.addHrSample(hr = 101, speedKmh = 3.5f * KM_PER_MILE, timestamp = 30_100)

        assertTrue(adjustments.isEmpty())
    }

    @Test
    fun updatedMaxSpeedAppliesToNextLiveDecision() {
        val adjustments = mutableListOf<Float>()
        val controller = AutoSpeedController(
            targetHr = 123,
            zoneMinHr = 114,
            zoneMaxHr = 133,
            maxSpeed = 6.0f,
            adjustmentIntervalSeconds = 30
        ).apply {
            onSpeedAdjustmentRequired = { adjustment -> adjustments += adjustment }
        }

        controller.updateMaxSpeed(4.0f)
        controller.addHrSample(hr = 100, speedKmh = 4.0f, timestamp = 100)
        controller.addHrSample(hr = 100, speedKmh = 4.0f, timestamp = 30_100)

        assertTrue(adjustments.isEmpty())
    }

    @Test
    fun belowZoneUsesSmallerStepBandsBeforeMaxAdjustment() {
        assertEquals(0.1f * KM_PER_MILE, belowZoneAdjustmentFor(avgHr = 112), 0.0001f)
        assertEquals(0.2f * KM_PER_MILE, belowZoneAdjustmentFor(avgHr = 109), 0.0001f)
        assertEquals(0.3f * KM_PER_MILE, belowZoneAdjustmentFor(avgHr = 106), 0.0001f)
        assertEquals(0.5f * KM_PER_MILE, belowZoneAdjustmentFor(avgHr = 96), 0.0001f)
    }

    @Test
    fun aboveZoneUsesSmallerStepBandsBeforeMaxAdjustment() {
        assertEquals(-0.1f * KM_PER_MILE, aboveZoneAdjustmentFor(avgHr = 135), 0.0001f)
        assertEquals(-0.2f * KM_PER_MILE, aboveZoneAdjustmentFor(avgHr = 138), 0.0001f)
        assertEquals(-0.3f * KM_PER_MILE, aboveZoneAdjustmentFor(avgHr = 141), 0.0001f)
        assertEquals(-0.5f * KM_PER_MILE, aboveZoneAdjustmentFor(avgHr = 151), 0.0001f)
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

    private fun belowZoneAdjustmentFor(avgHr: Int): Float =
        singleAdjustmentFor(hr = avgHr)

    private fun aboveZoneAdjustmentFor(avgHr: Int): Float =
        singleAdjustmentFor(hr = avgHr)

    private fun singleAdjustmentFor(hr: Int): Float {
        val adjustments = mutableListOf<Float>()
        val controller = AutoSpeedController(
            targetHr = 123,
            zoneMinHr = 114,
            zoneMaxHr = 133,
            adjustmentIntervalSeconds = 30
        ).apply {
            onSpeedAdjustmentRequired = { adjustment -> adjustments += adjustment }
        }

        controller.addHrSample(hr = hr, speedKmh = 4.0f, timestamp = 100)
        controller.addHrSample(hr = hr, speedKmh = 4.0f, timestamp = 30_100)

        assertEquals(1, adjustments.size)
        return adjustments.first()
    }

    private companion object {
        private const val KM_PER_MILE = 1.60934f
    }
}
