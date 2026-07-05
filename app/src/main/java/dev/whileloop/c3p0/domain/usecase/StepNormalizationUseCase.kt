package dev.whileloop.c3p0.domain.usecase

import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.repository.SessionRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

interface StepHistoryDataSource {
    suspend fun hasStepHistoryPermission(): Boolean
    suspend fun readRawSteps(startTime: Instant, endTime: Instant): List<StepCountRecord>
    suspend fun readAggregatedSteps(startTime: Instant, endTime: Instant): Long
}

data class StepCountRecord(
    val startTime: Instant,
    val endTime: Instant,
    val count: Long,
    val packageName: String
)

class StepNormalizationUseCase @Inject constructor(
    private val stepHistoryDataSource: StepHistoryDataSource,
    private val sessionRepository: SessionRepository
) {
    suspend fun canReadStepHistory(): Boolean =
        stepHistoryDataSource.hasStepHistoryPermission()

    suspend fun getNormalizedSteps(startTime: Instant, endTime: Instant): NormalizedStepsResult {
        val records = stepHistoryDataSource.readRawSteps(startTime, endTime)
        val totalRaw = stepHistoryDataSource.readAggregatedSteps(startTime, endTime)
        
        var c3p0Steps = 0L
        var overlappingOtherSteps = 0L
        
        for (record in records) {
            val windowCount = proratedCount(record, startTime, endTime)
            if (record.packageName == C3P0_PACKAGE_NAME) {
                c3p0Steps += windowCount
            } else {
                overlappingOtherSteps += windowCount
            }
        }
        
        return NormalizedStepsResult(
            totalRaw = totalRaw,
            normalized = totalRaw - overlappingOtherSteps,
            c3p0Steps = c3p0Steps,
            otherSteps = overlappingOtherSteps
        )
    }

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
        val normalizedEndDate = maxOf(startDate, endDate)
        val days = (normalizedEndDate.toEpochDay() - startDate.toEpochDay() + 1)
            .coerceAtLeast(1)
            .toInt()
        val startTime = startDate.atStartOfDay(zone).toInstant()
        val endTime = normalizedEndDate.plusDays(1).atStartOfDay(zone).toInstant()
        val records = stepHistoryDataSource.readRawSteps(startTime, endTime)
        val sessions = sessionRepository.getSessionsBetween(startTime, endTime)

        return (0 until days).map { offset ->
            val date = startDate.plusDays(offset.toLong())
            val dayStart = date.atStartOfDay(zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
            summarizeDay(
                date = date,
                dayStart = dayStart,
                dayEnd = dayEnd,
                rawSteps = stepHistoryDataSource.readAggregatedSteps(dayStart, dayEnd),
                records = records,
                sessions = sessions
            )
        }.reversed()
    }

    suspend fun getTodayNormalizedSteps(): Long =
        getDailyStepHistory(days = 1).firstOrNull()?.normalizedSteps ?: 0L

    private fun summarizeDay(
        date: LocalDate,
        dayStart: Instant,
        dayEnd: Instant,
        rawSteps: Long,
        records: List<StepCountRecord>,
        sessions: List<SessionEntity>
    ): DailyStepHistory {
        val daySessions = sessions.filter { session ->
            val sessionEnd = session.endTime ?: return@filter false
            session.startTime < dayEnd && sessionEnd > dayStart
        }
        var c3p0Steps = 0L
        var excludedOtherSessionSteps = 0L

        records.forEach { record ->
            val dayCount = proratedCount(record, dayStart, dayEnd)

            if (record.packageName == C3P0_PACKAGE_NAME) {
                c3p0Steps += dayCount
            } else {
                excludedOtherSessionSteps += daySessions.sumOf { session ->
                    val sessionEnd = session.endTime ?: return@sumOf 0L
                    proratedCount(record, maxOf(dayStart, session.startTime), minOf(dayEnd, sessionEnd))
                }
            }
        }

        return DailyStepHistory(
            date = date,
            rawSteps = rawSteps,
            normalizedSteps = (rawSteps - excludedOtherSessionSteps).coerceAtLeast(0L),
            c3p0Steps = c3p0Steps,
            excludedOtherSessionSteps = excludedOtherSessionSteps
        )
    }

    private fun proratedCount(record: StepCountRecord, windowStart: Instant, windowEnd: Instant): Long {
        val overlapStart = maxOf(record.startTime, windowStart)
        val overlapEnd = minOf(record.endTime, windowEnd)
        if (!overlapEnd.isAfter(overlapStart)) return 0L

        val recordMillis = record.endTime.toEpochMilli() - record.startTime.toEpochMilli()
        if (recordMillis <= 0L) return record.count

        val overlapMillis = overlapEnd.toEpochMilli() - overlapStart.toEpochMilli()
        return ((record.count.toDouble() * overlapMillis) / recordMillis).toLong()
    }

    companion object {
        private const val DEFAULT_HISTORY_DAYS = 180
        private const val C3P0_PACKAGE_NAME = "dev.whileloop.c3p0"
    }
}

data class NormalizedStepsResult(
    val totalRaw: Long,
    val normalized: Long,
    val c3p0Steps: Long,
    val otherSteps: Long
)

data class DailyStepHistory(
    val date: LocalDate,
    val rawSteps: Long,
    val normalizedSteps: Long,
    val c3p0Steps: Long,
    val excludedOtherSessionSteps: Long
)
