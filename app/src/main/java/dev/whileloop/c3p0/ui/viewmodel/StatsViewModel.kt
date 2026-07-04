package dev.whileloop.c3p0.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.whileloop.c3p0.data.model.UnitSystem
import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.entity.SessionMetricEntity
import dev.whileloop.c3p0.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whileloop.c3p0.data.repository.SettingsRepository
import dev.whileloop.c3p0.domain.usecase.DailyStepHistory
import dev.whileloop.c3p0.domain.usecase.NormalizedStepsResult
import dev.whileloop.c3p0.domain.usecase.StepNormalizationUseCase
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
    private val stepNormalizationUseCase: StepNormalizationUseCase
) : ViewModel() {
    private var selectedMetricsJob: Job? = null
    private var normalizedStepsJob: Job? = null

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

    private val _selectedSession = MutableStateFlow<SessionEntity?>(null)
    val selectedSession = _selectedSession.asStateFlow()

    private val _selectedSessionMetrics = MutableStateFlow<List<SessionMetricEntity>>(emptyList())
    val selectedSessionMetrics: StateFlow<List<SessionMetricEntity>> = _selectedSessionMetrics

    private val _normalizedSteps = MutableStateFlow<NormalizedStepsResult?>(null)
    val normalizedSteps = _normalizedSteps.asStateFlow()

    private val _dailyStepHistory = MutableStateFlow<List<DailyStepHistory>>(emptyList())
    val dailyStepHistory = _dailyStepHistory.asStateFlow()

    private val _canReadHealthConnectSteps = MutableStateFlow(false)
    val canReadHealthConnectSteps = _canReadHealthConnectSteps.asStateFlow()

    private val _isStepHistoryLoading = MutableStateFlow(false)
    val isStepHistoryLoading = _isStepHistoryLoading.asStateFlow()

    init {
        refreshStepHistory()
    }

    fun selectSession(session: SessionEntity) {
        _selectedSession.value = session
        selectedMetricsJob?.cancel()
        normalizedStepsJob?.cancel()
        selectedMetricsJob = viewModelScope.launch {
            val sessionId = session.id
            repository.getMetricsForSession(sessionId).collect {
                _selectedSessionMetrics.value = it
            }
        }
        normalizedStepsJob = viewModelScope.launch {
            val endTime = session.endTime ?: return@launch
            _normalizedSteps.value = stepNormalizationUseCase.getNormalizedSteps(session.startTime, endTime)
        }
    }

    fun clearSelectedSession() {
        selectedMetricsJob?.cancel()
        normalizedStepsJob?.cancel()
        selectedMetricsJob = null
        normalizedStepsJob = null
        _selectedSession.value = null
        _selectedSessionMetrics.value = emptyList()
        _normalizedSteps.value = null
    }

    fun refreshStepHistory() {
        viewModelScope.launch {
            _isStepHistoryLoading.value = true
            try {
                val canReadSteps = stepNormalizationUseCase.canReadStepHistory()
                _canReadHealthConnectSteps.value = canReadSteps
                _dailyStepHistory.value = if (canReadSteps) {
                    stepNormalizationUseCase.getDailyStepHistory()
                } else {
                    emptyList()
                }
            } finally {
                _isStepHistoryLoading.value = false
            }
        }
    }
}
