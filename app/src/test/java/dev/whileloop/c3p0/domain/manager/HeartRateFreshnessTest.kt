package dev.whileloop.c3p0.domain.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateFreshnessTest {
    @Test
    fun recentPositiveHeartRateIsFresh() {
        assertTrue(
            isHeartRateFresh(
                heartRate = 120,
                lastReceivedAtMillis = 8_000L,
                nowElapsedMillis = 10_000L
            )
        )
    }

    @Test
    fun oldHeartRateIsStale() {
        assertFalse(
            isHeartRateFresh(
                heartRate = 120,
                lastReceivedAtMillis = 4_999L,
                nowElapsedMillis = 10_000L
            )
        )
    }

    @Test
    fun missingHeartRateIsStale() {
        assertFalse(isHeartRateFresh(heartRate = 0, lastReceivedAtMillis = 9_000L, nowElapsedMillis = 10_000L))
        assertFalse(isHeartRateFresh(heartRate = 120, lastReceivedAtMillis = 0L, nowElapsedMillis = 10_000L))
    }
}
