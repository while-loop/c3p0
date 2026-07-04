package dev.whileloop.c3p0.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.whileloop.c3p0.ble.manager.ConnectionState
import dev.whileloop.c3p0.ble.manager.HeartRateManager
import dev.whileloop.c3p0.ble.manager.TreadmillManager
import dev.whileloop.c3p0.ble.model.TreadmillMode
import dev.whileloop.c3p0.data.model.UnitSystem
import dev.whileloop.c3p0.data.repository.SettingsRepository
import dev.whileloop.c3p0.domain.manager.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whileloop.c3p0.domain.usecase.StepNormalizationUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val treadmillManager: TreadmillManager,
    private val heartRateManager: HeartRateManager,
    private val settingsRepository: SettingsRepository,
    private val stepNormalizationUseCase: StepNormalizationUseCase
) : ViewModel() {
    private var elapsedJob: Job? = null
    private var heartRateHistoryJob: Job? = null
    private var statsStarted = false
    private var activeHeartRateTotal = 0
    private var activeHeartRateSampleCount = 0
    private var sessionStartDistance = 0
    private var sessionStartSteps = 0
    private var normalizedStepsToday: Int? = null

    private val _sessionElapsedSeconds = MutableStateFlow(0)
    val sessionElapsedSeconds = _sessionElapsedSeconds.asStateFlow()

    private val _sessionDistance = MutableStateFlow(0)
    val sessionDistance = _sessionDistance.asStateFlow()

    private val _sessionSteps = MutableStateFlow(0)
    val sessionSteps = _sessionSteps.asStateFlow()

    private val _heartRateHistory = MutableStateFlow<List<Int>>(emptyList())
    val heartRateHistory = _heartRateHistory.asStateFlow()

    private val _averageHeartRate = MutableStateFlow(0)
    val averageHeartRate = _averageHeartRate.asStateFlow()

    private val _normalizedStepsToGoal = MutableStateFlow<Int?>(null)
    val normalizedStepsToGoal = _normalizedStepsToGoal.asStateFlow()

    val treadmillStatus = treadmillManager.status.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        treadmillManager.status.value
    )

    val connectionState = treadmillManager.connectionState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        treadmillManager.connectionState.value
    )

    val watchConnectionState = heartRateManager.connectionState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        heartRateManager.connectionState.value
    )

    val currentHeartRate = heartRateManager.heartRate.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        heartRateManager.heartRate.value
    )

    val lastHeartRateReceivedAtMillis = heartRateManager.lastHeartRateReceivedAtMillis.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        heartRateManager.lastHeartRateReceivedAtMillis.value
    )

    val isSessionActive = sessionManager.isSessionActive.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        sessionManager.isSessionActive.value
    )

    val isSessionPaused = sessionManager.isSessionPaused.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        sessionManager.isSessionPaused.value
    )

    val isAutoSpeedEnabled = sessionManager.isAutoSpeedEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        sessionManager.isAutoSpeedEnabled.value
    )

    val unitSystem = settingsRepository.unitSystem.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UnitSystem.Imperial
    )

    val age = settingsRepository.age.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        30
    )

    val skipInactiveDeviceWarning = settingsRepository.skipInactiveDeviceWarning.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    val bodyWeightKg = settingsRepository.bodyWeightKg.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null
    )

    val stepGoal = settingsRepository.stepGoal.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DEFAULT_STEP_GOAL
    )

    init {
        viewModelScope.launch {
            settingsRepository.unitSystem.distinctUntilChanged().collect { storedUnitSystem ->
                treadmillManager.setUnitSystem(storedUnitSystem)
            }
        }

        viewModelScope.launch {
            treadmillManager.status
                .map { it.unitSystem }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { reportedUnitSystem ->
                    val storedUnitSystem = settingsRepository.unitSystem.first()
                    if (reportedUnitSystem != storedUnitSystem) {
                        treadmillManager.setUnitSystem(storedUnitSystem)
                    }
                }
        }

        viewModelScope.launch {
            combine(sessionManager.isSessionActive, sessionManager.isSessionPaused) { isActive, isPaused ->
                isActive to isPaused
            }.collect { (isActive, isPaused) ->
                when {
                    isActive && !isPaused -> {
                        startSessionStats(reset = !statsStarted)
                        statsStarted = true
                    }
                    else -> {
                        stopSessionStats()
                        if (!isActive) {
                            statsStarted = false
                            refreshNormalizedStepsToGoal()
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            combine(treadmillManager.status, sessionManager.isSessionActive) { status, isActive ->
                status to isActive
            }.collect { (status, isActive) ->
                if (isActive) {
                    _sessionDistance.value = counterDelta(sessionStartDistance, status.distance)
                    _sessionSteps.value = counterDelta(sessionStartSteps, status.steps)
                    updateNormalizedStepsToGoal()
                }
            }
        }

        viewModelScope.launch {
            settingsRepository.stepGoal.distinctUntilChanged().collect {
                updateNormalizedStepsToGoal()
            }
        }

        viewModelScope.launch {
            settingsRepository.treadmillAddress
                .filterNotNull()
                .distinctUntilChanged()
                .collect { address ->
                    if (treadmillManager.connectionState.value == ConnectionState.DISCONNECTED) {
                        treadmillManager.connect(address)
                    }
                }
        }

        viewModelScope.launch {
            settingsRepository.watchAddress
                .filterNotNull()
                .distinctUntilChanged()
                .collect { address ->
                    if (heartRateManager.connectionState.value == ConnectionState.DISCONNECTED) {
                        heartRateManager.connect(address)
                    }
                }
        }

        refreshNormalizedStepsToGoal()
    }

    fun startSession() {
        viewModelScope.launch {
            treadmillManager.setUnitSystem(settingsRepository.unitSystem.first())
            sessionManager.startSession()
        }
    }

    fun stopSession() {
        viewModelScope.launch {
            treadmillManager.setUnitSystem(settingsRepository.unitSystem.first())
            sessionManager.stopSession()
        }
    }

    fun pauseSession() {
        viewModelScope.launch {
            treadmillManager.setUnitSystem(settingsRepository.unitSystem.first())
            sessionManager.pauseSession()
        }
    }

    fun resumeSession() {
        viewModelScope.launch {
            treadmillManager.setUnitSystem(settingsRepository.unitSystem.first())
            sessionManager.resumeSession()
        }
    }

    fun updateSkipInactiveDeviceWarning(skip: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveSkipInactiveDeviceWarning(skip)
        }
    }

    fun refreshNormalizedStepsToGoal() {
        viewModelScope.launch {
            if (!stepNormalizationUseCase.canReadStepHistory()) {
                normalizedStepsToday = null
                _normalizedStepsToGoal.value = null
                return@launch
            }
            normalizedStepsToday = stepNormalizationUseCase.getTodayNormalizedSteps().toInt()
            updateNormalizedStepsToGoal()
        }
    }

    private fun updateNormalizedStepsToGoal() {
        val normalizedSteps = normalizedStepsToday ?: run {
            _normalizedStepsToGoal.value = null
            return
        }
        val inProgressSteps = if (sessionManager.isSessionActive.value) _sessionSteps.value else 0
        _normalizedStepsToGoal.value = (stepGoal.value - normalizedSteps - inProgressSteps).coerceAtLeast(0)
    }

    fun incrementSpeed() {
        viewModelScope.launch {
            val current = treadmillStatus.value.speed
            treadmillManager.setSpeed((current + SPEED_STEP_KMH).coerceAtMost(MAX_SPEED_KMH))
        }
    }

    fun decrementSpeed() {
        viewModelScope.launch {
            val current = treadmillStatus.value.speed
            treadmillManager.setSpeed((current - SPEED_STEP_KMH).coerceAtLeast(MIN_SPEED_KMH))
        }
    }

    fun setMode(mode: TreadmillMode) {
        viewModelScope.launch {
            sessionManager.disableAutoSpeed()
            treadmillManager.setMode(mode)
        }
    }

    fun enableZone2Mode() {
        viewModelScope.launch {
            treadmillManager.setMode(TreadmillMode.MANUAL)
            sessionManager.enableAutoSpeed(targetZone2HeartRate(age.value))
        }
    }

    fun disableAutoSpeed() {
        viewModelScope.launch {
            treadmillManager.setMode(TreadmillMode.MANUAL)
            sessionManager.disableAutoSpeed()
        }
    }

    private fun targetZone2HeartRate(age: Int): Int {
        val maxHeartRate = 220 - age
        return (maxHeartRate * 0.65f).toInt()
    }

    private fun startSessionStats(reset: Boolean) {
        if (reset) {
            _sessionElapsedSeconds.value = 0
            _heartRateHistory.value = emptyList()
            _averageHeartRate.value = 0
            sessionStartDistance = treadmillStatus.value.distance
            sessionStartSteps = treadmillStatus.value.steps
            _sessionDistance.value = 0
            _sessionSteps.value = 0
            activeHeartRateTotal = 0
            activeHeartRateSampleCount = 0
        }

        elapsedJob?.cancel()
        elapsedJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _sessionElapsedSeconds.value += 1
            }
        }

        heartRateHistoryJob?.cancel()
        heartRateHistoryJob = viewModelScope.launch {
            heartRateManager.heartRate.collect { heartRate ->
                if (sessionManager.isSessionPaused.value) return@collect
                if (heartRate <= 0) return@collect

                val updated = (_heartRateHistory.value + heartRate).takeLast(MAX_HEART_RATE_SAMPLES)
                _heartRateHistory.value = updated
                activeHeartRateTotal += heartRate
                activeHeartRateSampleCount += 1
                _averageHeartRate.value = activeHeartRateTotal / activeHeartRateSampleCount
            }
        }
    }

    private fun stopSessionStats() {
        elapsedJob?.cancel()
        elapsedJob = null
        heartRateHistoryJob?.cancel()
        heartRateHistoryJob = null
    }

    companion object {
        private const val MAX_HEART_RATE_SAMPLES = 180
        private const val SPEED_STEP_KMH = 0.1f
        private const val MIN_SPEED_KMH = 0f
        private const val MAX_SPEED_KMH = 6f
        private const val DEFAULT_STEP_GOAL = 10000
    }

    private fun counterDelta(start: Int, end: Int): Int =
        if (end >= start) end - start else end
}
