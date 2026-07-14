package dev.whileloop.c3p0.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.whileloop.c3p0.data.model.CachedDailyStepHistory
import dev.whileloop.c3p0.data.model.CachedWeightHistoryRecord
import dev.whileloop.c3p0.data.model.UnitSystem
import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.entity.SessionMetricEntity
import dev.whileloop.c3p0.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whileloop.c3p0.data.repository.SettingsRepository
import dev.whileloop.c3p0.data.repository.StepHistoryCacheRepository
import dev.whileloop.c3p0.data.repository.WeightHistoryCacheRepository
import dev.whileloop.c3p0.domain.usecase.DailyStepHistory
import dev.whileloop.c3p0.domain.usecase.StepHistoryUseCase
import dev.whileloop.c3p0.health.HealthConnectManager
import dev.whileloop.c3p0.health.HealthConnectHistoryRefresher
import dev.whileloop.c3p0.health.WeightHistoryRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repository: SessionRepository,
    settingsRepository: SettingsRepository,
    private val stepHistoryUseCase: StepHistoryUseCase,
    private val stepHistoryCacheRepository: StepHistoryCacheRepository,
    private val weightHistoryCacheRepository: WeightHistoryCacheRepository,
    private val healthConnectManager: HealthConnectManager,
    private val healthConnectHistoryRefresher: HealthConnectHistoryRefresher
) : ViewModel() {
    private var selectedMetricsJob: Job? = null

    val allSessions = repository.getAllSessions().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val unitSystem = settingsRepository.unitSystem.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UnitSystem.Imperial
    )

    val stepGoal = settingsRepository.stepGoal.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DEFAULT_STEP_GOAL
    )

    private val _selectedSession = MutableStateFlow<SessionEntity?>(null)
    val selectedSession = _selectedSession.asStateFlow()

    private val _selectedSessionMetrics = MutableStateFlow<List<SessionMetricEntity>>(emptyList())
    val selectedSessionMetrics: StateFlow<List<SessionMetricEntity>> = _selectedSessionMetrics

    private val _dailyStepHistory = MutableStateFlow<List<DailyStepHistory>>(emptyList())
    val dailyStepHistory = _dailyStepHistory.asStateFlow()

    private val _canReadHealthConnectSteps = MutableStateFlow(false)
    val canReadHealthConnectSteps = _canReadHealthConnectSteps.asStateFlow()

    private val _isStepHistoryLoading = MutableStateFlow(false)
    val isStepHistoryLoading = _isStepHistoryLoading.asStateFlow()

    private val _weightHistory = MutableStateFlow<List<WeightHistoryRecord>>(emptyList())
    val weightHistory = _weightHistory.asStateFlow()

    private val _canReadHealthConnectWeight = MutableStateFlow(false)
    val canReadHealthConnectWeight = _canReadHealthConnectWeight.asStateFlow()

    private val _isWeightHistoryLoading = MutableStateFlow(false)
    val isWeightHistoryLoading = _isWeightHistoryLoading.asStateFlow()

    init {
        loadCachedThenRefreshStepHistory()
        loadCachedThenRefreshWeightHistory()
        viewModelScope.launch {
            healthConnectHistoryRefresher.stepHistoryUpdates.collect { rows ->
                _dailyStepHistory.value = rows.map { it.toDailyStepHistory() }
            }
        }
        viewModelScope.launch {
            healthConnectHistoryRefresher.weightHistoryUpdates.collect { records ->
                _weightHistory.value = records.map { it.toWeightHistoryRecord() }
            }
        }
    }

    fun selectSession(session: SessionEntity) {
        _selectedSession.value = session
        selectedMetricsJob?.cancel()
        selectedMetricsJob = viewModelScope.launch {
            val sessionId = session.id
            repository.getMetricsForSession(sessionId).collect {
                _selectedSessionMetrics.value = it
            }
        }
    }

    fun clearSelectedSession() {
        selectedMetricsJob?.cancel()
        selectedMetricsJob = null
        _selectedSession.value = null
        _selectedSessionMetrics.value = emptyList()
    }

    private fun loadCachedThenRefreshStepHistory() {
        viewModelScope.launch {
            val cachedHistory = stepHistoryCacheRepository.readDailyStepHistory()
            if (cachedHistory.isNotEmpty()) {
                _dailyStepHistory.value = cachedHistory.map { it.toDailyStepHistory() }
            }
            refreshStepHistoryFromCache(
                cachedHistory = cachedHistory,
                lastFetchDate = stepHistoryCacheRepository.readLastFetchDate()
            )
        }
    }

    private fun loadCachedThenRefreshWeightHistory() {
        viewModelScope.launch {
            val cachedHistory = weightHistoryCacheRepository.readWeightHistory()
            if (cachedHistory.isNotEmpty()) {
                _weightHistory.value = cachedHistory.map { it.toWeightHistoryRecord() }
            }
            refreshWeightHistoryFromCache(
                cachedHistory = cachedHistory,
                lastFetchTime = weightHistoryCacheRepository.readLastFetchTime()
            )
        }
    }

    fun refreshStepHistory() {
        viewModelScope.launch {
            refreshStepHistoryWith { healthConnectHistoryRefresher.refreshRecentSteps() }
        }
    }

    fun refreshFullStepHistory() {
        viewModelScope.launch {
            refreshStepHistoryWith { healthConnectHistoryRefresher.refreshFullSteps() }
        }
    }

    fun refreshWeightHistory() {
        viewModelScope.launch {
            refreshWeightHistoryWith { healthConnectHistoryRefresher.refreshRecentWeight() }
        }
    }

    fun refreshFullWeightHistory() {
        viewModelScope.launch {
            refreshWeightHistoryWith { healthConnectHistoryRefresher.refreshFullWeight() }
        }
    }

    private suspend fun refreshStepHistoryWith(
        refresh: suspend () -> List<CachedDailyStepHistory>
    ) {
        _isStepHistoryLoading.value = true
        try {
            val canRead = stepHistoryUseCase.canReadStepHistory()
            _canReadHealthConnectSteps.value = canRead
            if (canRead) {
                _dailyStepHistory.value = refresh().map { it.toDailyStepHistory() }
            }
        } finally {
            _isStepHistoryLoading.value = false
        }
    }

    private suspend fun refreshWeightHistoryWith(
        refresh: suspend () -> List<CachedWeightHistoryRecord>
    ) {
        _isWeightHistoryLoading.value = true
        try {
            val canRead = healthConnectManager.hasWeightHistoryPermission()
            _canReadHealthConnectWeight.value = canRead
            if (canRead) {
                _weightHistory.value = refresh().map { it.toWeightHistoryRecord() }
            }
        } finally {
            _isWeightHistoryLoading.value = false
        }
    }

    private suspend fun refreshStepHistoryFromCache(
        cachedHistory: List<CachedDailyStepHistory>,
        lastFetchDate: LocalDate?
    ) {
        val defaultStartDate = defaultStepHistoryStartDate()
        val cachedStartDate = cachedHistory.minOfOrNull { it.date }
        val incrementalStartDate = (lastFetchDate ?: cachedHistory.maxOfOrNull { it.date })
            ?.minusDays(INCREMENTAL_REFRESH_BUFFER_DAYS)
            ?: defaultStartDate
        val startDate = if (cachedStartDate == null || cachedStartDate.isAfter(defaultStartDate)) {
            defaultStartDate
        } else {
            incrementalStartDate
        }
        refreshStepHistoryRange(startDate = startDate, cachedHistory = cachedHistory)
    }

    private suspend fun refreshStepHistoryRange(
        startDate: LocalDate,
        cachedHistory: List<CachedDailyStepHistory>
    ) {
        _isStepHistoryLoading.value = true
        try {
            val canReadSteps = stepHistoryUseCase.canReadStepHistory()
            _canReadHealthConnectSteps.value = canReadSteps
            if (canReadSteps) {
                val today = LocalDate.now(ZoneId.systemDefault())
                val freshHistory = stepHistoryUseCase.getDailyStepHistory(
                    startDate = startDate,
                    endDate = today
                )
                val mergedHistory = mergeStepHistory(
                    cachedHistory = cachedHistory,
                    freshHistory = freshHistory
                )
                _dailyStepHistory.value = mergedHistory.map { it.toDailyStepHistory() }
                stepHistoryCacheRepository.saveDailyStepHistory(
                    rows = mergedHistory,
                    fetchedThroughDate = today
                )
            }
        } finally {
            _isStepHistoryLoading.value = false
        }
    }

    private suspend fun refreshWeightHistoryFromCache(
        cachedHistory: List<CachedWeightHistoryRecord>,
        lastFetchTime: Instant?
    ) {
        val startTime = (lastFetchTime ?: cachedHistory.maxOfOrNull { it.time })
            ?.minus(Duration.ofDays(INCREMENTAL_REFRESH_BUFFER_DAYS))
            ?: defaultWeightHistoryStartTime()
        refreshWeightHistoryRange(startTime = startTime, cachedHistory = cachedHistory)
    }

    private suspend fun refreshWeightHistoryRange(
        startTime: Instant,
        cachedHistory: List<CachedWeightHistoryRecord>
    ) {
        _isWeightHistoryLoading.value = true
        try {
            val canReadWeight = healthConnectManager.hasWeightHistoryPermission()
            _canReadHealthConnectWeight.value = canReadWeight
            if (canReadWeight) {
                val endTime = nextLocalMidnight()
                val freshHistory = healthConnectManager
                    .readWeightHistory(startTime, endTime)
                    .map { it.toCachedWeightHistoryRecord() }
                val mergedHistory = mergeWeightHistory(
                    cachedHistory = cachedHistory,
                    freshHistory = freshHistory
                )
                _weightHistory.value = mergedHistory.map { it.toWeightHistoryRecord() }
                weightHistoryCacheRepository.saveWeightHistory(
                    records = mergedHistory,
                    fetchedThroughTime = endTime
                )
            }
        } finally {
            _isWeightHistoryLoading.value = false
        }
    }

    private fun CachedDailyStepHistory.toDailyStepHistory(): DailyStepHistory =
        DailyStepHistory(
            date = date,
            steps = steps
        )

    private fun DailyStepHistory.toCachedDailyStepHistory(): CachedDailyStepHistory =
        CachedDailyStepHistory(
            date = date,
            steps = steps
        )

    private fun CachedWeightHistoryRecord.toWeightHistoryRecord(): WeightHistoryRecord =
        WeightHistoryRecord(
            time = time,
            weightKg = weightKg
        )

    private fun WeightHistoryRecord.toCachedWeightHistoryRecord(): CachedWeightHistoryRecord =
        CachedWeightHistoryRecord(
            time = time,
            weightKg = weightKg
        )

    private fun mergeStepHistory(
        cachedHistory: List<CachedDailyStepHistory>,
        freshHistory: List<DailyStepHistory>
    ): List<CachedDailyStepHistory> =
        (cachedHistory.associateBy { it.date } +
            freshHistory.associate { it.date to it.toCachedDailyStepHistory() })
            .values
            .sortedByDescending { it.date }

    private fun mergeWeightHistory(
        cachedHistory: List<CachedWeightHistoryRecord>,
        freshHistory: List<CachedWeightHistoryRecord>
    ): List<CachedWeightHistoryRecord> =
        (cachedHistory.associateBy { it.time } +
            freshHistory.associateBy { it.time })
            .values
            .sortedBy { it.time }

    private fun defaultStepHistoryStartDate(): LocalDate {
        val zone = ZoneId.systemDefault()
        return LocalDate.now(zone).minusDays((DEFAULT_STEP_HISTORY_DAYS - 1).toLong())
    }

    private fun defaultWeightHistoryStartTime(): Instant =
        LocalDate.now(ZoneId.systemDefault())
            .minusDays((DEFAULT_WEIGHT_HISTORY_DAYS - 1).toLong())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()

    private fun nextLocalMidnight(): Instant {
        val zone = ZoneId.systemDefault()
        return LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
    }

    private companion object {
        private const val DEFAULT_STEP_GOAL = 10_000
        private const val DEFAULT_STEP_HISTORY_DAYS = 365
        private const val DEFAULT_WEIGHT_HISTORY_DAYS = 180
        private const val INCREMENTAL_REFRESH_BUFFER_DAYS = 7L
    }
}
