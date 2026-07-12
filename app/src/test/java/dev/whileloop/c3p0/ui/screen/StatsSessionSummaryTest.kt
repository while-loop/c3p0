package dev.whileloop.c3p0.ui.screen

import dev.whileloop.c3p0.data.entity.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class StatsSessionSummaryTest {
    @Test
    fun averageStepsPerMinuteUsesCompletedSessionDuration() {
        val start = Instant.parse("2026-07-11T12:00:00Z")
        val session = SessionEntity(
            startTime = start,
            endTime = start.plusSeconds(12 * 60),
            totalSteps = 1_080
        )

        assertEquals(90, averageSessionStepsPerMinute(session))
    }

    @Test
    fun averageStepsPerMinuteIsUnavailableForUnfinishedOrZeroLengthSession() {
        val start = Instant.parse("2026-07-11T12:00:00Z")

        assertNull(averageSessionStepsPerMinute(SessionEntity(startTime = start, totalSteps = 100)))
        assertNull(
            averageSessionStepsPerMinute(
                SessionEntity(startTime = start, endTime = start, totalSteps = 100)
            )
        )
    }
}
