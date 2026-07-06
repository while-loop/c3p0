package dev.whileloop.c3p0.domain.usecase

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StepHistoryUseCaseTest {
    private val zone = ZoneId.systemDefault()

    @Test
    fun getStepsReturnsHealthConnectAggregate() = runBlocking {
        val start = Instant.parse("2026-07-04T10:00:00Z")
        val end = Instant.parse("2026-07-04T11:00:00Z")
        val dataSource = FakeStepHistoryDataSource(
            aggregateSteps = mapOf(start to 2_000L)
        )
        val useCase = StepHistoryUseCase(dataSource)

        val result = useCase.getSteps(start, end)

        assertEquals(2_000L, result)
        assertEquals(start, dataSource.firstAggregateStartTime)
        assertEquals(end, dataSource.lastAggregateEndTime)
    }

    @Test
    fun dailyHistoryReadsAggregatesForEachDay() = runBlocking {
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val yesterdayStart = yesterday.atStartOfDay(zone).toInstant()
        val todayStart = today.atStartOfDay(zone).toInstant()
        val todayEnd = today.plusDays(1).atStartOfDay(zone).toInstant()
        val dataSource = FakeStepHistoryDataSource(
            aggregateSteps = mapOf(
                yesterdayStart to 4_000L,
                todayStart to 5_000L
            )
        )
        val useCase = StepHistoryUseCase(dataSource)

        val result = useCase.getDailyStepHistory(startDate = yesterday, endDate = today)

        assertEquals(listOf(today, yesterday), result.map { it.date })
        assertEquals(listOf(5_000L, 4_000L), result.map { it.steps })
        assertEquals(yesterdayStart, dataSource.firstAggregateStartTime)
        assertEquals(todayEnd, dataSource.lastAggregateEndTime)
    }

    @Test
    fun defaultDailyHistoryRequestsOneYear() = runBlocking {
        val today = LocalDate.now(zone)
        val expectedStart = today.minusDays(364).atStartOfDay(zone).toInstant()
        val expectedEnd = today.plusDays(1).atStartOfDay(zone).toInstant()
        val dataSource = FakeStepHistoryDataSource()
        val useCase = StepHistoryUseCase(dataSource)

        val result = useCase.getDailyStepHistory()

        assertEquals(365, result.size)
        assertEquals(expectedStart, dataSource.firstAggregateStartTime)
        assertEquals(expectedEnd, dataSource.lastAggregateEndTime)
    }

    private class FakeStepHistoryDataSource(
        private val aggregateSteps: Map<Instant, Long> = emptyMap(),
        private val hasPermission: Boolean = true
    ) : StepHistoryDataSource {
        var firstAggregateStartTime: Instant? = null
            private set
        var lastAggregateEndTime: Instant? = null
            private set

        override suspend fun hasStepHistoryPermission(): Boolean = hasPermission

        override suspend fun readAggregatedSteps(startTime: Instant, endTime: Instant): Long {
            if (firstAggregateStartTime == null) {
                firstAggregateStartTime = startTime
            }
            lastAggregateEndTime = endTime
            return aggregateSteps[startTime] ?: 0L
        }
    }
}
