package dev.whileloop.c3p0.domain.usecase

import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.repository.SessionRepository
import dev.whileloop.c3p0.data.entity.SessionMetricEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StepNormalizationUseCaseTest {
    private val zone = ZoneId.systemDefault()

    @Test
    fun selectedSessionSubtractsOtherSourceStepsAndKeepsC3P0Steps() = runBlocking {
        val start = instant("2026-07-04T10:00:00Z")
        val end = instant("2026-07-04T11:00:00Z")
        val dataSource = FakeStepHistoryDataSource(
            records = listOf(
                stepRecord("dev.whileloop.c3p0", "2026-07-04T10:00:00Z", "2026-07-04T11:00:00Z", 500),
                stepRecord("com.watch", "2026-07-04T10:00:00Z", "2026-07-04T11:00:00Z", 800),
                stepRecord("com.phone", "2026-07-04T09:30:00Z", "2026-07-04T10:30:00Z", 600)
            ),
            aggregateSteps = mapOf(start to 2_000L)
        )
        val useCase = StepNormalizationUseCase(dataSource, FakeSessionRepository())

        val result = useCase.getNormalizedSteps(start, end)

        assertEquals(2_000L, result.totalRaw)
        assertEquals(900L, result.normalized)
        assertEquals(500L, result.c3p0Steps)
        assertEquals(1_100L, result.otherSteps)
    }

    @Test
    fun dailyHistoryProratesRecordsAcrossDayBoundary() = runBlocking {
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant()
        val dataSource = FakeStepHistoryDataSource(
            records = listOf(
                StepCountRecord(
                    startTime = today.minusDays(1).atTime(23, 30).atZone(zone).toInstant(),
                    endTime = today.atTime(0, 30).atZone(zone).toInstant(),
                    count = 600,
                    packageName = "dev.whileloop.c3p0"
                ),
                StepCountRecord(
                    startTime = today.atTime(23, 30).atZone(zone).toInstant(),
                    endTime = today.plusDays(1).atTime(0, 30).atZone(zone).toInstant(),
                    count = 900,
                    packageName = "com.watch"
                )
            ),
            aggregateSteps = mapOf(dayStart to 1_200L)
        )
        val useCase = StepNormalizationUseCase(dataSource, FakeSessionRepository())

        val result = useCase.getDailyStepHistory(days = 1).single()

        assertEquals(today, result.date)
        assertEquals(dayEnd, dataSource.lastAggregateEndTime)
        assertEquals(1_200L, result.rawSteps)
        assertEquals(1_200L, result.normalizedSteps)
        assertEquals(300L, result.c3p0Steps)
        assertEquals(0L, result.excludedOtherSessionSteps)
    }

    @Test
    fun dailyHistoryExcludesOtherSourceStepsFromMultipleC3P0SessionsInOneDay() = runBlocking {
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant()
        val firstSessionStart = today.atTime(10, 0).atZone(zone).toInstant()
        val firstSessionEnd = today.atTime(10, 30).atZone(zone).toInstant()
        val secondSessionStart = today.atTime(14, 0).atZone(zone).toInstant()
        val secondSessionEnd = today.atTime(14, 30).atZone(zone).toInstant()
        val sessions = listOf(
            session(firstSessionStart, firstSessionEnd, steps = 450),
            session(secondSessionStart, secondSessionEnd, steps = 650)
        )
        val dataSource = FakeStepHistoryDataSource(
            records = listOf(
                StepCountRecord(firstSessionStart, firstSessionEnd, 450, "dev.whileloop.c3p0"),
                StepCountRecord(firstSessionStart, firstSessionEnd, 500, "com.watch"),
                StepCountRecord(secondSessionStart, secondSessionEnd, 650, "dev.whileloop.c3p0"),
                StepCountRecord(secondSessionStart, secondSessionEnd, 700, "com.watch"),
                stepRecord(
                    packageName = "com.watch",
                    start = today.atTime(16, 0).atZone(zone).toInstant(),
                    end = today.atTime(17, 0).atZone(zone).toInstant(),
                    count = 8_800
                )
            ),
            aggregateSteps = mapOf(dayStart to 10_000L)
        )
        val useCase = StepNormalizationUseCase(dataSource, FakeSessionRepository(sessions))

        val result = useCase.getDailyStepHistory(days = 1).single()

        assertEquals(10_000L, result.rawSteps)
        assertEquals(8_800L, result.normalizedSteps)
        assertEquals(1_100L, result.c3p0Steps)
        assertEquals(1_200L, result.excludedOtherSessionSteps)
    }

    private fun instant(value: String): Instant = Instant.parse(value)

    private fun stepRecord(
        packageName: String,
        startIso: String,
        endIso: String,
        count: Long
    ): StepCountRecord =
        StepCountRecord(
            startTime = instant(startIso),
            endTime = instant(endIso),
            count = count,
            packageName = packageName
        )

    private fun stepRecord(
        packageName: String,
        start: Instant,
        end: Instant,
        count: Long
    ): StepCountRecord =
        StepCountRecord(
            startTime = start,
            endTime = end,
            count = count,
            packageName = packageName
        )

    private fun session(start: Instant, end: Instant, steps: Int): SessionEntity =
        SessionEntity(
            startTime = start,
            endTime = end,
            totalSteps = steps
        )

    private class FakeStepHistoryDataSource(
        private val records: List<StepCountRecord> = emptyList(),
        private val aggregateSteps: Map<Instant, Long> = emptyMap(),
        private val hasPermission: Boolean = true
    ) : StepHistoryDataSource {
        var lastAggregateEndTime: Instant? = null
            private set

        override suspend fun hasStepHistoryPermission(): Boolean = hasPermission

        override suspend fun readRawSteps(startTime: Instant, endTime: Instant): List<StepCountRecord> =
            records.filter { it.startTime < endTime && it.endTime > startTime }

        override suspend fun readAggregatedSteps(startTime: Instant, endTime: Instant): Long {
            lastAggregateEndTime = endTime
            return aggregateSteps[startTime] ?: 0L
        }
    }

    private class FakeSessionRepository(
        private val sessions: List<SessionEntity> = emptyList()
    ) : SessionRepository {
        override suspend fun startSession(): Long = error("Not used")

        override suspend fun endSession(id: Long, finalStats: SessionEntity) = Unit

        override suspend fun addMetric(metric: SessionMetricEntity) = Unit

        override fun getAllSessions(): Flow<List<SessionEntity>> = flowOf(sessions)

        override suspend fun getSessionsBetween(startTime: Instant, endTime: Instant): List<SessionEntity> =
            sessions.filter { session ->
                val sessionEnd = session.endTime ?: return@filter false
                session.startTime < endTime && sessionEnd > startTime
            }

        override fun getMetricsForSession(sessionId: Long): Flow<List<SessionMetricEntity>> =
            flowOf(emptyList())
    }
}
