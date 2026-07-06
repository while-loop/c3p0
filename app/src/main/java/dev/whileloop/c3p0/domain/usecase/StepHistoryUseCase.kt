package dev.whileloop.c3p0.domain.usecase

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

interface StepHistoryDataSource {
    suspend fun hasStepHistoryPermission(): Boolean
    suspend fun readAggregatedSteps(startTime: Instant, endTime: Instant): Long
}

class StepHistoryUseCase @Inject constructor(
    private val stepHistoryDataSource: StepHistoryDataSource
) {
    suspend fun canReadStepHistory(): Boolean =
        stepHistoryDataSource.hasStepHistoryPermission()

    suspend fun getSteps(startTime: Instant, endTime: Instant): Long =
        stepHistoryDataSource.readAggregatedSteps(startTime, endTime)

    suspend fun getDailyStepHistory(days: Int = DEFAULT_HISTORY_DAYS): List<DailyStepHistory> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDate = today.minusDays((days - 1).coerceAtLeast(0).toLong())
        return getDailyStepHistory(startDate = startDate, endDate = today)
    }

    suspend fun getDailyStepHistory(
        startDate: LocalDate,
        endDate: LocalDate
    ): List<DailyStepHistory> {
        val zone = ZoneId.systemDefault()
        val endDateInclusive = maxOf(startDate, endDate)
        val days = (endDateInclusive.toEpochDay() - startDate.toEpochDay() + 1)
            .coerceAtLeast(1)
            .toInt()

        return (0 until days).map { offset ->
            val date = startDate.plusDays(offset.toLong())
            val dayStart = date.atStartOfDay(zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
            DailyStepHistory(
                date = date,
                steps = stepHistoryDataSource.readAggregatedSteps(dayStart, dayEnd)
            )
        }.reversed()
    }

    suspend fun getTodaySteps(): Long =
        getDailyStepHistory(days = 1).firstOrNull()?.steps ?: 0L

    private companion object {
        private const val DEFAULT_HISTORY_DAYS = 365
    }
}

data class DailyStepHistory(
    val date: LocalDate,
    val steps: Long
)
