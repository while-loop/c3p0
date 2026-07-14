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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private var lifecycleRefreshJob: Job? = null
    private val cachedHistoryLoaded = CompletableDeferred<Unit>()

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
        loadCachedHistories()
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

    private fun loadCachedHistories() {
        viewModelScope.launch {
            try {
                val cachedSteps = stepHistoryCacheRepository.readDailyStepHistory()
                if (cachedSteps.isNotEmpty()) {
                    _dailyStepHistory.value = cachedSteps.map { it.toDailyStepHistory() }
                }
                val cachedWeight = weightHistoryCacheRepository.readWeightHistory()
                if (cachedWeight.isNotEmpty()) {
                    _weightHistory.value = cachedWeight.map { it.toWeightHistoryRecord() }
                }
            } finally {
                cachedHistoryLoaded.complete(Unit)
            }
        }
    }

    fun refreshSinceLastFetch() {
        if (lifecycleRefreshJob?.isActive == true || _isStepHistoryLoading.value || _isWeightHistoryLoading.value) return
        lifecycleRefreshJob = viewModelScope.launch {
            cachedHistoryLoaded.await()
            _isStepHistoryLoading.value = true
            _isWeightHistoryLoading.value = true
            try {
                _canReadHealthConnectSteps.value = stepHistoryUseCase.canReadStepHistory()
                _canReadHealthConnectWeight.value = healthConnectManager.hasWeightHistoryPermission()
                healthConnectHistoryRefresher.refreshSinceLastFetch()
            } finally {
                _isStepHistoryLoading.value = false
                _isWeightHistoryLoading.value = false
            }
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

    private fun CachedDailyStepHistory.toDailyStepHistory(): DailyStepHistory =
        DailyStepHistory(
            date = date,
            steps = steps
        )

    private fun CachedWeightHistoryRecord.toWeightHistoryRecord(): WeightHistoryRecord =
        WeightHistoryRecord(
            time = time,
            weightKg = weightKg
        )

    private companion object {
        private const val DEFAULT_STEP_GOAL = 10_000
    }
}
