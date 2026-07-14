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
import dev.whileloop.c3p0.data.entity.ActiveSessionCheckpointEntity
import dev.whileloop.c3p0.data.repository.SettingsRepository
import dev.whileloop.c3p0.domain.manager.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whileloop.c3p0.domain.usecase.StepHistoryUseCase
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
    private val stepHistoryUseCase: StepHistoryUseCase
) : ViewModel() {
    private var heartRateHistoryJob: Job? = null
    private var speedCommandJob: Job? = null
    private var pendingManualSpeedKmh: Float? = null
    private var statsStarted = false
    private var activeHeartRateTotal = 0L
    private var activeHeartRateSampleCount = 0
    private var sessionStartDistance = 0
    private var sessionStartSteps = 0
    private var sessionStartCalories = 0
    private var sessionStartPadTime = 0
    private var stepsToday: Int? = null
    private var stepRateSamples = emptyList<StepRateSample>()
    private var pendingRecoveryCheckpoint: ActiveSessionCheckpointEntity? = null
    private var sessionElapsedOffset = 0
    private var sessionDistanceOffset = 0
    private var sessionStepsOffset = 0
    private var sessionCaloriesOffset = 0

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

    private val _stepsToGoal = MutableStateFlow<Int?>(null)
    val stepsToGoal = _stepsToGoal.asStateFlow()

    private val _estimatedSecondsToStepGoal = MutableStateFlow<Int?>(null)
    val estimatedSecondsToStepGoal = _estimatedSecondsToStepGoal.asStateFlow()

    private val _recentStepsPerMinute = MutableStateFlow<Float?>(null)
    val recentStepsPerMinute = _recentStepsPerMinute.asStateFlow()

    private val _isSessionStarting = MutableStateFlow(false)
    val isSessionStarting = _isSessionStarting.asStateFlow()

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

    val recoverableSession = sessionManager.recoverableSession.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        sessionManager.recoverableSession.value
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
                            refreshStepsToGoal()
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
                    _sessionElapsedSeconds.value = sessionElapsedOffset + counterDelta(sessionStartPadTime, status.time)
                    _sessionDistance.value = sessionDistanceOffset + distanceDelta
                    _sessionSteps.value = sessionStepsOffset + sessionStepDelta(status.hasStepCount, status.steps, distanceDelta)
                    _sessionCalories.value = sessionCaloriesOffset + sessionCalorieDelta(status.calories)
                    updateStepRateSamples(
                        elapsedSeconds = _sessionElapsedSeconds.value,
                        sessionSteps = _sessionSteps.value
                    )
                    updateStepsToGoal()
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
            settingsRepository.stepGoal.distinctUntilChanged().collect {
                updateStepsToGoal()
            }
        }

        viewModelScope.launch {
            sessionManager.healthConnectSessionWrites.collect {
                refreshStepsToGoal()
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

        refreshStepsToGoal()
    }

    fun startSession() {
        if (!_isSessionStarting.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                applyDefaultStartMode()
                sessionManager.startSession()
                sessionManager.isSessionStarting.first { isStarting -> !isStarting }
            } finally {
                _isSessionStarting.value = false
            }
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

    fun resumeRecoveredSession() {
        pendingRecoveryCheckpoint = sessionManager.recoverableSession.value?.checkpoint ?: return
        sessionManager.resumeRecoveredSession()
    }

    fun finishRecoveredSession() {
        sessionManager.finishRecoveredSession()
    }

    fun discardRecoveredSession() {
        sessionManager.discardRecoveredSession()
    }

    fun updateSkipInactiveDeviceWarning(skip: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveSkipInactiveDeviceWarning(skip)
        }
    }

    fun refreshStepsToGoal() {
        viewModelScope.launch {
            if (!stepHistoryUseCase.canReadStepHistory()) {
                stepsToday = null
                _stepsToGoal.value = null
                return@launch
            }
            stepsToday = stepHistoryUseCase.getTodaySteps().toInt()
            updateStepsToGoal()
        }
    }

    private fun updateStepsToGoal() {
        val stepsPerMinute = if (sessionManager.isSessionActive.value) {
            calculateRecentStepsPerMinute(stepRateSamples)
        } else {
            null
        }
        _recentStepsPerMinute.value = stepsPerMinute
        val currentSteps = stepsToday ?: run {
            _stepsToGoal.value = null
            _estimatedSecondsToStepGoal.value = null
            return
        }
        val inProgressSteps = if (sessionManager.isSessionActive.value) _sessionSteps.value else 0
        _stepsToGoal.value = (stepGoal.value - currentSteps - inProgressSteps).coerceAtLeast(0)
        updateEstimatedSecondsToStepGoal(stepsPerMinute)
    }

    private fun updateEstimatedSecondsToStepGoal(stepsPerMinute: Float?) {
        val remainingSteps = _stepsToGoal.value ?: run {
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

        val recentRate = stepsPerMinute ?: run {
            _estimatedSecondsToStepGoal.value = null
            return
        }
        if (recentRate < MIN_STEPS_PER_MINUTE_FOR_GOAL_ETA) {
            _estimatedSecondsToStepGoal.value = null
            return
        }

        _estimatedSecondsToStepGoal.value = ((remainingSteps / recentRate) * 60f)
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
            val recovery = pendingRecoveryCheckpoint
            sessionElapsedOffset = recovery?.elapsedSeconds ?: 0
            sessionDistanceOffset = recovery?.totalDistance ?: 0
            sessionStepsOffset = recovery?.totalSteps ?: 0
            sessionCaloriesOffset = recovery?.totalEnergy ?: 0
            _sessionElapsedSeconds.value = sessionElapsedOffset
            _heartRateHistory.value = emptyList()
            activeHeartRateTotal = recovery?.heartRateTotal ?: 0L
            activeHeartRateSampleCount = recovery?.heartRateSampleCount ?: 0
            _averageHeartRate.value = if (activeHeartRateSampleCount > 0) {
                (activeHeartRateTotal / activeHeartRateSampleCount).toInt()
            } else {
                0
            }
            sessionStartDistance = treadmillStatus.value.distance
            sessionStartSteps = treadmillStatus.value.steps
            sessionStartCalories = treadmillStatus.value.calories
            sessionStartPadTime = treadmillStatus.value.time
            _sessionDistance.value = sessionDistanceOffset
            _sessionSteps.value = sessionStepsOffset
            _sessionCalories.value = sessionCaloriesOffset
            stepRateSamples = emptyList()
            pendingRecoveryCheckpoint = null
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
                _averageHeartRate.value = (activeHeartRateTotal / activeHeartRateSampleCount).toInt()
            }
        }
    }

    private fun stopSessionStats() {
        heartRateHistoryJob?.cancel()
        heartRateHistoryJob = null
        _recentStepsPerMinute.value = null
    }

    private fun updateStepRateSamples(elapsedSeconds: Int, sessionSteps: Int) {
        if (elapsedSeconds < 0 || sessionSteps < 0) return
        val nextSample = StepRateSample(elapsedSeconds, sessionSteps)
        val withoutDuplicateTimestamp = stepRateSamples.dropLastWhile {
            it.elapsedSeconds >= elapsedSeconds
        }
        val updated = withoutDuplicateTimestamp + nextSample
        val cutoff = elapsedSeconds - STEP_GOAL_ETA_WINDOW_SECONDS
        val firstKeptIndex = updated.indexOfFirst { it.elapsedSeconds >= cutoff }
            .let { index ->
                when {
                    index <= 0 -> 0
                    else -> index - 1
                }
        }
        stepRateSamples = updated.drop(firstKeptIndex)
    }

    companion object {
        private const val MAX_HEART_RATE_SAMPLES = 180
        private const val SPEED_STEP_KMH = 0.1f
        private const val SPEED_STEP_MPH = 0.1f
        private const val KM_PER_MILE = 1.60934f
        private const val MIN_SPEED_KMH = 1.60934f
        private const val MAX_SPEED_KMH = 6f
        private const val SPEED_APPLIED_TOLERANCE_KMH = 0.11f
        private const val PENDING_SPEED_TIMEOUT_MS = 3_000L
        private const val HEART_RATE_FRESHNESS_WINDOW_MS = 5_000L
        private const val DEFAULT_STEP_GOAL = 10000
        internal const val STEP_GOAL_ETA_WINDOW_SECONDS = 180
        internal const val MIN_STEP_RATE_SAMPLE_SECONDS = 30
        internal const val MIN_STEP_RATE_SAMPLE_STEPS = 10
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

internal data class StepRateSample(
    val elapsedSeconds: Int,
    val sessionSteps: Int
)

internal fun calculateRecentStepsPerMinute(samples: List<StepRateSample>): Float? {
    val latest = samples.lastOrNull() ?: return null
    val cutoff = latest.elapsedSeconds - SessionViewModel.STEP_GOAL_ETA_WINDOW_SECONDS
    val baseline = samples
        .filter { it.elapsedSeconds <= cutoff }
        .lastOrNull()
        ?: samples.firstOrNull()
        ?: return null
    val elapsedSeconds = latest.elapsedSeconds - baseline.elapsedSeconds
    val stepDelta = latest.sessionSteps - baseline.sessionSteps
    if (elapsedSeconds < SessionViewModel.MIN_STEP_RATE_SAMPLE_SECONDS ||
        stepDelta < SessionViewModel.MIN_STEP_RATE_SAMPLE_STEPS
    ) {
        return null
    }
    return stepDelta / (elapsedSeconds / 60f)
}
