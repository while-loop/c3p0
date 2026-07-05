package dev.whileloop.c3p0.ui.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.whileloop.c3p0.ble.manager.ConnectionState
import dev.whileloop.c3p0.ble.manager.HeartRateManager
import dev.whileloop.c3p0.ble.manager.TreadmillManager
import dev.whileloop.c3p0.ble.model.TreadmillMode
import dev.whileloop.c3p0.data.model.SessionStartMode
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
import kotlin.math.abs
import kotlin.math.roundToInt

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val treadmillManager: TreadmillManager,
    private val heartRateManager: HeartRateManager,
    private val settingsRepository: SettingsRepository,
    private val stepNormalizationUseCase: StepNormalizationUseCase
) : ViewModel() {
    private var heartRateHistoryJob: Job? = null
    private var speedCommandJob: Job? = null
    private var pendingManualSpeedKmh: Float? = null
    private var statsStarted = false
    private var activeHeartRateTotal = 0
    private var activeHeartRateSampleCount = 0
    private var sessionStartDistance = 0
    private var sessionStartSteps = 0
    private var sessionStartCalories = 0
    private var sessionStartPadTime = 0
    private var normalizedStepsToday: Int? = null

    private val _sessionElapsedSeconds = MutableStateFlow(0)
    val sessionElapsedSeconds = _sessionElapsedSeconds.asStateFlow()

    private val _sessionDistance = MutableStateFlow(0)
    val sessionDistance = _sessionDistance.asStateFlow()

    private val _sessionSteps = MutableStateFlow(0)
    val sessionSteps = _sessionSteps.asStateFlow()

    private val _sessionCalories = MutableStateFlow(0)
    val sessionCalories = _sessionCalories.asStateFlow()

    private val _heartRateHistory = MutableStateFlow<List<Int>>(emptyList())
    val heartRateHistory = _heartRateHistory.asStateFlow()

    private val _averageHeartRate = MutableStateFlow(0)
    val averageHeartRate = _averageHeartRate.asStateFlow()

    private val _normalizedStepsToGoal = MutableStateFlow<Int?>(null)
    val normalizedStepsToGoal = _normalizedStepsToGoal.asStateFlow()

    private val _estimatedSecondsToStepGoal = MutableStateFlow<Int?>(null)
    val estimatedSecondsToStepGoal = _estimatedSecondsToStepGoal.asStateFlow()

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

    val sessionStartMode = settingsRepository.sessionStartMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SessionStartMode.Zone2
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

    val keepScreenOnDuringActiveSession = settingsRepository.keepScreenOnDuringActiveSession.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
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
                    val distanceDelta = counterDelta(sessionStartDistance, status.distance)
                    _sessionElapsedSeconds.value = counterDelta(sessionStartPadTime, status.time)
                    _sessionDistance.value = distanceDelta
                    _sessionSteps.value = sessionStepDelta(status.hasStepCount, status.steps, distanceDelta)
                    _sessionCalories.value = sessionCalorieDelta(status.calories)
                    updateNormalizedStepsToGoal()
                }
            }
        }

        viewModelScope.launch {
            treadmillManager.status.collect { status ->
                val pendingSpeed = pendingManualSpeedKmh ?: return@collect
                if (abs(status.speed - pendingSpeed) <= SPEED_APPLIED_TOLERANCE_KMH) {
                    pendingManualSpeedKmh = null
                }
            }
        }

        viewModelScope.launch {
            while (true) {
                delay(HEART_RATE_ZONE2_GUARD_INTERVAL_MS)
                if (sessionManager.isAutoSpeedEnabled.value && !hasFreshHeartRateData()) {
                    exitZone2ForMissingHeartRate()
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
            applyDefaultStartMode()
            sessionManager.startSession()
        }
    }

    fun stopSession() {
        sessionManager.stopSession()
    }

    fun pauseSession() {
        sessionManager.pauseSession()
    }

    fun resumeSession() {
        sessionManager.resumeSession()
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
            _estimatedSecondsToStepGoal.value = null
            return
        }
        val inProgressSteps = if (sessionManager.isSessionActive.value) _sessionSteps.value else 0
        _normalizedStepsToGoal.value = (stepGoal.value - normalizedSteps - inProgressSteps).coerceAtLeast(0)
        updateEstimatedSecondsToStepGoal()
    }

    private fun updateEstimatedSecondsToStepGoal() {
        val remainingSteps = _normalizedStepsToGoal.value ?: run {
            _estimatedSecondsToStepGoal.value = null
            return
        }
        if (remainingSteps <= 0) {
            _estimatedSecondsToStepGoal.value = 0
            return
        }
        if (!sessionManager.isSessionActive.value) {
            _estimatedSecondsToStepGoal.value = null
            return
        }

        val elapsedSeconds = _sessionElapsedSeconds.value
        val sessionSteps = _sessionSteps.value
        if (elapsedSeconds < MIN_STEP_RATE_SAMPLE_SECONDS || sessionSteps < MIN_STEP_RATE_SAMPLE_STEPS) {
            _estimatedSecondsToStepGoal.value = null
            return
        }

        val stepsPerMinute = sessionSteps / (elapsedSeconds / 60f)
        if (stepsPerMinute < MIN_STEPS_PER_MINUTE_FOR_GOAL_ETA) {
            _estimatedSecondsToStepGoal.value = null
            return
        }

        _estimatedSecondsToStepGoal.value = ((remainingSteps / stepsPerMinute) * 60f)
            .roundToInt()
            .coerceAtLeast(1)
    }

    fun incrementSpeed() {
        adjustManualSpeed(manualSpeedStepKmh())
    }

    fun decrementSpeed() {
        adjustManualSpeed(-manualSpeedStepKmh())
    }

    private fun manualSpeedStepKmh(): Float =
        if (unitSystem.value == UnitSystem.Imperial) {
            SPEED_STEP_MPH * KM_PER_MILE
        } else {
            SPEED_STEP_KMH
        }

    private fun adjustManualSpeed(deltaKmh: Float) {
        val targetSpeed = ((pendingManualSpeedKmh ?: treadmillStatus.value.speed) + deltaKmh)
            .coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)
        pendingManualSpeedKmh = targetSpeed
        speedCommandJob?.cancel()
        speedCommandJob = viewModelScope.launch {
            val sent = treadmillManager.setSpeed(targetSpeed)
            if (!sent) {
                if (pendingManualSpeedKmh == targetSpeed) {
                    pendingManualSpeedKmh = null
                }
                return@launch
            }
            delay(PENDING_SPEED_TIMEOUT_MS)
            if (pendingManualSpeedKmh == targetSpeed) {
                pendingManualSpeedKmh = null
            }
        }
    }

    private suspend fun applyDefaultStartMode() {
        when (sessionStartMode.value) {
            SessionStartMode.Manual -> {
                sessionManager.disableAutoSpeed()
                treadmillManager.setMode(TreadmillMode.MANUAL)
            }
            SessionStartMode.Zone2 -> {
                if (hasFreshHeartRateData()) {
                    enableZone2ModeNow()
                } else {
                    sessionManager.disableAutoSpeed()
                    treadmillManager.setMode(TreadmillMode.MANUAL)
                }
            }
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
            if (!hasFreshHeartRateData()) return@launch
            enableZone2ModeNow()
        }
    }

    private suspend fun enableZone2ModeNow() {
        val zone = zone2HeartRateRange(age.value)
        treadmillManager.setMode(TreadmillMode.MANUAL)
        sessionManager.enableAutoSpeed(
            targetHr = zone.target,
            zoneMinHr = zone.min,
            zoneMaxHr = zone.max
        )
    }

    fun disableAutoSpeed() {
        viewModelScope.launch {
            treadmillManager.setMode(TreadmillMode.MANUAL)
            sessionManager.disableAutoSpeed()
        }
    }

    private suspend fun exitZone2ForMissingHeartRate() {
        sessionManager.disableAutoSpeed()
        treadmillManager.setMode(TreadmillMode.MANUAL)
        treadmillManager.setSpeed(MIN_SPEED_KMH)
    }

    private fun hasFreshHeartRateData(nowElapsedMillis: Long = SystemClock.elapsedRealtime()): Boolean =
        currentHeartRate.value > 0 &&
            lastHeartRateReceivedAtMillis.value > 0L &&
            nowElapsedMillis - lastHeartRateReceivedAtMillis.value <= HEART_RATE_FRESHNESS_WINDOW_MS

    private fun zone2HeartRateRange(age: Int): HeartRateRange {
        val maxHeartRate = 220 - age
        return HeartRateRange(
            min = (maxHeartRate * 0.60f).toInt(),
            target = (maxHeartRate * 0.65f).toInt(),
            max = (maxHeartRate * 0.70f).toInt()
        )
    }

    private fun startSessionStats(reset: Boolean) {
        if (reset) {
            _sessionElapsedSeconds.value = 0
            _heartRateHistory.value = emptyList()
            _averageHeartRate.value = 0
            sessionStartDistance = treadmillStatus.value.distance
            sessionStartSteps = treadmillStatus.value.steps
            sessionStartCalories = treadmillStatus.value.calories
            sessionStartPadTime = treadmillStatus.value.time
            _sessionDistance.value = 0
            _sessionSteps.value = 0
            _sessionCalories.value = 0
            activeHeartRateTotal = 0
            activeHeartRateSampleCount = 0
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
        heartRateHistoryJob?.cancel()
        heartRateHistoryJob = null
    }

    companion object {
        private const val MAX_HEART_RATE_SAMPLES = 180
        private const val SPEED_STEP_KMH = 0.1f
        private const val SPEED_STEP_MPH = 0.1f
        private const val KM_PER_MILE = 1.60934f
        private const val MIN_SPEED_KMH = 1.60934f
        private const val MAX_SPEED_KMH = 6f
        private const val SPEED_APPLIED_TOLERANCE_KMH = 0.05f
        private const val PENDING_SPEED_TIMEOUT_MS = 3_000L
        private const val HEART_RATE_FRESHNESS_WINDOW_MS = 5_000L
        private const val HEART_RATE_ZONE2_GUARD_INTERVAL_MS = 1_000L
        private const val DEFAULT_STEP_GOAL = 10000
        private const val MIN_STEP_RATE_SAMPLE_SECONDS = 30
        private const val MIN_STEP_RATE_SAMPLE_STEPS = 10
        private const val MIN_STEPS_PER_MINUTE_FOR_GOAL_ETA = 1f
        private const val ESTIMATED_STRIDE_LENGTH_METERS = 0.75
    }

    private fun counterDelta(start: Int, end: Int): Int =
        if (end >= start) end - start else end

    private fun sessionStepDelta(hasStepCount: Boolean, steps: Int, distanceDelta: Int): Int =
        if (hasStepCount) {
            counterDelta(sessionStartSteps, steps)
        } else {
            estimateStepsFromDistance(distanceDelta)
        }

    private fun sessionCalorieDelta(calories: Int): Int =
        counterDelta(sessionStartCalories, calories)

    private fun estimateStepsFromDistance(distanceDelta: Int): Int =
        ((distanceDelta * 10.0) / ESTIMATED_STRIDE_LENGTH_METERS).roundToInt().coerceAtLeast(0)

    private data class HeartRateRange(
        val min: Int,
        val target: Int,
        val max: Int
    )
}
