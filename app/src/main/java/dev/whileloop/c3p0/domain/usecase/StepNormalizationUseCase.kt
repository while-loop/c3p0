package dev.whileloop.c3p0.domain.usecase

import androidx.health.connect.client.records.StepsRecord
import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.repository.SessionRepository
import dev.whileloop.c3p0.health.HealthConnectManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

class StepNormalizationUseCase @Inject constructor(
    private val healthConnectManager: HealthConnectManager,
    private val sessionRepository: SessionRepository
) {
    suspend fun canReadStepHistory(): Boolean =
        healthConnectManager.hasStepHistoryPermission()

    suspend fun getNormalizedSteps(startTime: Instant, endTime: Instant): NormalizedStepsResult {
        val records = healthConnectManager.readRawSteps(startTime, endTime)
        
        var totalRaw = 0L
        var c3p0Steps = 0L
        var overlappingOtherSteps = 0L
        
        for (record in records) {
            val windowCount = proratedCount(record, startTime, endTime)
            totalRaw += windowCount
            if (record.metadata.dataOrigin.packageName == C3P0_PACKAGE_NAME) {
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
        val startTime = startDate.atStartOfDay(zone).toInstant()
        val endTime = today.plusDays(1).atStartOfDay(zone).toInstant()
        val records = healthConnectManager.readRawSteps(startTime, endTime)
        val sessions = sessionRepository.getSessionsBetween(startTime, endTime)

        return (0 until days).map { offset ->
            val date = startDate.plusDays(offset.toLong())
            val dayStart = date.atStartOfDay(zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
            summarizeDay(date, dayStart, dayEnd, records, sessions)
        }.reversed()
    }

    private fun summarizeDay(
        date: LocalDate,
        dayStart: Instant,
        dayEnd: Instant,
        records: List<StepsRecord>,
        sessions: List<SessionEntity>
    ): DailyStepHistory {
        val daySessions = sessions.filter { session ->
            val sessionEnd = session.endTime ?: return@filter false
            session.startTime < dayEnd && sessionEnd > dayStart
        }
        var rawSteps = 0L
        var c3p0Steps = 0L
        var excludedOtherSessionSteps = 0L

        records.forEach { record ->
            val dayCount = proratedCount(record, dayStart, dayEnd)
            rawSteps += dayCount

            if (record.metadata.dataOrigin.packageName == C3P0_PACKAGE_NAME) {
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

    private fun proratedCount(record: StepsRecord, windowStart: Instant, windowEnd: Instant): Long {
        val overlapStart = maxOf(record.startTime, windowStart)
        val overlapEnd = minOf(record.endTime, windowEnd)
        if (!overlapEnd.isAfter(overlapStart)) return 0L

        val recordMillis = record.endTime.toEpochMilli() - record.startTime.toEpochMilli()
        if (recordMillis <= 0L) return record.count

        val overlapMillis = overlapEnd.toEpochMilli() - overlapStart.toEpochMilli()
        return ((record.count.toDouble() * overlapMillis) / recordMillis).toLong()
    }

    companion object {
        private const val DEFAULT_HISTORY_DAYS = 14
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
