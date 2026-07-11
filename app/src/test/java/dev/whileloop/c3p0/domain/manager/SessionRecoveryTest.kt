package dev.whileloop.c3p0.domain.manager

import dev.whileloop.c3p0.data.entity.ActiveSessionCheckpointEntity
import dev.whileloop.c3p0.data.entity.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SessionRecoveryTest {
    @Test
    fun completedSessionUsesDurableCheckpointTotals() {
        val start = Instant.parse("2026-07-11T12:00:00Z")
        val checkpointTime = start.plusSeconds(600)
        val recovered = RecoverableSession(
            session = SessionEntity(id = 7, startTime = start),
            checkpoint = ActiveSessionCheckpointEntity(
                sessionId = 7,
                checkpointTime = checkpointTime,
                elapsedSeconds = 540,
                totalDistance = 123,
                totalSteps = 1_640,
                totalEnergy = 88,
                heartRateTotal = 500,
                heartRateSampleCount = 4,
                maxHeartRate = 141,
                wasPaused = false
            )
        )

        val completed = recovered.toCompletedSession()

        assertEquals(start, completed.startTime)
        assertEquals(checkpointTime, completed.endTime)
        assertEquals(123, completed.totalDistance)
        assertEquals(1_640, completed.totalSteps)
        assertEquals(88, completed.totalEnergy)
        assertEquals(125, completed.averageHeartRate)
        assertEquals(141, completed.maxHeartRate)
    }

    @Test
    fun completedSessionAlwaysEndsAfterItStarts() {
        val start = Instant.parse("2026-07-11T12:00:00Z")
        val recovered = RecoverableSession(
            session = SessionEntity(id = 9, startTime = start),
            checkpoint = ActiveSessionCheckpointEntity(
                sessionId = 9,
                checkpointTime = start,
                elapsedSeconds = 0,
                totalDistance = 0,
                totalSteps = 0,
                totalEnergy = 0,
                heartRateTotal = 0,
                heartRateSampleCount = 0,
                maxHeartRate = 0,
                wasPaused = true
            )
        )

        val completed = recovered.toCompletedSession()

        assertTrue(completed.endTime!!.isAfter(completed.startTime))
        assertEquals(0, completed.averageHeartRate)
    }
}
