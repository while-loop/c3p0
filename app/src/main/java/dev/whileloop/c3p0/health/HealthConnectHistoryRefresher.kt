package dev.whileloop.c3p0.health

import dev.whileloop.c3p0.data.model.CachedDailyStepHistory
import dev.whileloop.c3p0.data.model.CachedWeightHistoryRecord
import dev.whileloop.c3p0.data.repository.StepHistoryCacheRepository
import dev.whileloop.c3p0.data.repository.WeightHistoryCacheRepository
import dev.whileloop.c3p0.domain.usecase.StepHistoryUseCase
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectHistoryRefresher @Inject constructor(
    private val stepHistoryUseCase: StepHistoryUseCase,
    private val healthConnectManager: HealthConnectManager,
    private val stepCache: StepHistoryCacheRepository,
    private val weightCache: WeightHistoryCacheRepository
) {
    private val _stepHistoryUpdates = MutableSharedFlow<List<CachedDailyStepHistory>>(extraBufferCapacity = 1)
    val stepHistoryUpdates = _stepHistoryUpdates.asSharedFlow()

    private val _weightHistoryUpdates = MutableSharedFlow<List<CachedWeightHistoryRecord>>(extraBufferCapacity = 1)
    val weightHistoryUpdates = _weightHistoryUpdates.asSharedFlow()

    suspend fun refreshSinceLastFetch() = coroutineScope {
        val steps = async {
            val lastFetch = stepCache.readLastFetchDate()
            refreshSteps(lastFetch?.minusDays(REFRESH_OVERLAP_DAYS) ?: recentStepStartDate())
        }
        val weight = async {
            val lastFetch = weightCache.readLastFetchTime()
            refreshWeight(lastFetch?.minus(Duration.ofDays(REFRESH_OVERLAP_DAYS)) ?: recentWeightStartTime())
        }
        steps.await()
        weight.await()
    }

    suspend fun refreshRecentSteps(): List<CachedDailyStepHistory> =
        refreshSteps(recentStepStartDate())

    suspend fun refreshFullSteps(): List<CachedDailyStepHistory> =
        refreshSteps(fullStepStartDate())

    suspend fun refreshRecentWeight(): List<CachedWeightHistoryRecord> =
        refreshWeight(recentWeightStartTime())

    suspend fun refreshFullWeight(): List<CachedWeightHistoryRecord> =
        refreshWeight(fullWeightStartTime())

    private suspend fun refreshSteps(startDate: LocalDate): List<CachedDailyStepHistory> {
        val cached = stepCache.readDailyStepHistory()
        if (!stepHistoryUseCase.canReadStepHistory()) return cached
        val today = LocalDate.now(ZoneId.systemDefault())
        val fresh = stepHistoryUseCase.getDailyStepHistory(startDate, today)
            .map { CachedDailyStepHistory(it.date, it.steps) }
        val merged = (cached.associateBy { it.date } + fresh.associateBy { it.date })
            .values.sortedByDescending { it.date }
        stepCache.saveDailyStepHistory(merged, today)
        _stepHistoryUpdates.tryEmit(merged)
        return merged
    }

    private suspend fun refreshWeight(startTime: Instant): List<CachedWeightHistoryRecord> {
        val cached = weightCache.readWeightHistory()
        if (!healthConnectManager.hasWeightHistoryPermission()) return cached
        val endTime = nextLocalMidnight()
        val fresh = healthConnectManager.readWeightHistory(startTime, endTime)
            .map { CachedWeightHistoryRecord(it.time, it.weightKg) }
        val merged = (cached.associateBy { it.time } + fresh.associateBy { it.time })
            .values.sortedBy { it.time }
        weightCache.saveWeightHistory(merged, endTime)
        _weightHistoryUpdates.tryEmit(merged)
        return merged
    }

    private fun recentStepStartDate(): LocalDate =
        LocalDate.now(ZoneId.systemDefault()).minusDays(RECENT_DAYS - 1L)

    private fun recentWeightStartTime(): Instant =
        recentStepStartDate().atStartOfDay(ZoneId.systemDefault()).toInstant()

    private fun fullStepStartDate(): LocalDate =
        LocalDate.now(ZoneId.systemDefault()).minusDays(FULL_STEP_DAYS - 1L)

    private fun fullWeightStartTime(): Instant =
        LocalDate.now(ZoneId.systemDefault()).minusDays(FULL_WEIGHT_DAYS - 1L)
            .atStartOfDay(ZoneId.systemDefault()).toInstant()

    private fun nextLocalMidnight(): Instant {
        val zone = ZoneId.systemDefault()
        return LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
    }

    private companion object {
        private const val RECENT_DAYS = 7L
        private const val REFRESH_OVERLAP_DAYS = 7L
        private const val FULL_STEP_DAYS = 365L
        private const val FULL_WEIGHT_DAYS = 180L
    }
}
