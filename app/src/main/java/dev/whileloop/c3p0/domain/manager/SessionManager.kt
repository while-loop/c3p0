package dev.whileloop.c3p0.domain.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import dev.whileloop.c3p0.ble.manager.*
import dev.whileloop.c3p0.ble.model.TreadmillMode
import dev.whileloop.c3p0.data.entity.ActiveSessionCheckpointEntity
import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.entity.SessionMetricEntity
import dev.whileloop.c3p0.data.repository.SessionRepository
import dev.whileloop.c3p0.data.repository.SettingsRepository
import dev.whileloop.c3p0.domain.algorithm.AutoSpeedController
import dev.whileloop.c3p0.health.HealthConnectManager
import dev.whileloop.c3p0.health.HealthConnectSpeedSample
import dev.whileloop.c3p0.health.HealthConnectHistoryRefresher
import dev.whileloop.c3p0.integration.openGarminConnect
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class SessionManager @Inject constructor(
    private val context: android.content.Context,
    private val treadmillManager: TreadmillManager,
    private val heartRateManager: HeartRateManager,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val healthConnectManager: HealthConnectManager,
    private val healthConnectHistoryRefresher: HealthConnectHistoryRefresher,
    private val sessionSpeedCommandGate: SessionSpeedCommandGate
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sessionJob: Job? = null
    private var currentSessionId: Long? = null
    private var startTime: Instant? = null
    private var activeStartedAt: Instant? = null
    private var accumulatedActiveDuration: Duration = Duration.ZERO
    private var startDistance = 0
    private var startSteps = 0
    private var startCalories = 0
    private var startPadTime = 0
    private var activeHeartRateTotal = 0L
    private var activeHeartRateSampleCount = 0
    private var maxHeartRate = 0
    private var restoredElapsedSeconds = 0
    private var restoredDistance = 0
    private var restoredSteps = 0
    private var restoredCalories = 0
    private var lastCheckpointAt: Instant? = null
    private val checkpointMutex = Mutex()
    private val recoveryLoaded = CompletableDeferred<Unit>()

    private val _isAutoSpeedEnabled = MutableStateFlow(false)
    val isAutoSpeedEnabled = _isAutoSpeedEnabled.asStateFlow()

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive = _isSessionActive.asStateFlow()

    private val _isSessionStarting = MutableStateFlow(false)
    val isSessionStarting = _isSessionStarting.asStateFlow()

    private val _isSessionPaused = MutableStateFlow(false)
    val isSessionPaused = _isSessionPaused.asStateFlow()

    private val _isSessionFinalizing = MutableStateFlow(false)
    val isSessionFinalizing = _isSessionFinalizing.asStateFlow()

    private val _recoverableSession = MutableStateFlow<RecoverableSession?>(null)
    val recoverableSession = _recoverableSession.asStateFlow()

    private val _healthConnectSessionWrites = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val healthConnectSessionWrites = _healthConnectSessionWrites.asSharedFlow()

    private val zone2MaxSpeedKmh = settingsRepository.zone2MaxSpeedKmh.stateIn(
        scope,
        SharingStarted.Eagerly,
        DEFAULT_ZONE2_MAX_SPEED_KMH
    )

    private var autoSpeedController: AutoSpeedController? = null

    init {
        scope.launch {
            try {
                refreshRecoverableSession()
            } finally {
                recoveryLoaded.complete(Unit)
            }
        }
        scope.launch {
            zone2MaxSpeedKmh.collect { configuredMax ->
                val liveMax = configuredMax.coerceIn(AUTO_SPEED_MIN_KMH, AUTO_SPEED_MAX_KMH)
                autoSpeedController?.updateMaxSpeed(liveMax)
                if (_isAutoSpeedEnabled.value && treadmillManager.status.value.speed > liveMax) {
                    treadmillManager.setSpeed(liveMax)
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(HEART_RATE_ZONE2_GUARD_INTERVAL_MS)
                if (_isAutoSpeedEnabled.value && !isHeartRateFresh(
                        heartRate = heartRateManager.heartRate.value,
                        lastReceivedAtMillis = heartRateManager.lastHeartRateReceivedAtMillis.value,
                        nowElapsedMillis = SystemClock.elapsedRealtime(),
                        freshnessWindowMillis = HEART_RATE_FRESHNESS_WINDOW_MS
                    )
                ) {
                    disableAutoSpeed()
                    treadmillManager.setMode(TreadmillMode.MANUAL)
                    treadmillManager.setSpeed(AUTO_SPEED_MIN_KMH)
                }
            }
        }
    }

    fun startSession() {
        if (_isSessionActive.value || !_isSessionStarting.compareAndSet(expect = false, update = true)) return
        scope.launch {
            recoveryLoaded.await()
            if (_recoverableSession.value == null) {
                launchSession(null)
            } else {
                _isSessionStarting.value = false
            }
        }
    }

    fun resumeRecoveredSession() {
        val recovered = _recoverableSession.value ?: return
        launchSession(recovered)
    }

    fun pauseSession() {
        if (_isSessionActive.value.not() || _isSessionPaused.value) return
        sessionSpeedCommandGate.block()

        scope.launch {
            if (treadmillManager.pause()) {
                accumulateActiveDuration()
                _isSessionPaused.value = true
                saveCheckpoint(Instant.now(), wasPaused = true)
            } else if (_isSessionActive.value && !_isSessionPaused.value) {
                sessionSpeedCommandGate.allow()
            }
        }
    }

    fun resumeSession() {
        if (_isSessionActive.value.not() || _isSessionPaused.value.not()) return

        scope.launch {
            if (treadmillManager.start()) {
                activeStartedAt = Instant.now()
                _isSessionPaused.value = false
                sessionSpeedCommandGate.allow()
            }
        }
    }

    fun stopSession() {
        if (!_isSessionFinalizing.compareAndSet(expect = false, update = true)) return
        sessionSpeedCommandGate.block()
        scope.launch {
            try {
                if (!treadmillManager.stop()) {
                    return@launch
                }

                sessionJob?.cancelAndJoin()
                _isSessionActive.value = false
                _isSessionPaused.value = false
                disableAutoSpeed()

                context.stopService(Intent(context, dev.whileloop.c3p0.service.SessionService::class.java))

                val id = currentSessionId ?: return@launch
                val start = startTime ?: return@launch
                val end = Instant.now()
                accumulateActiveDuration(end)
                val finalStatus = treadmillManager.status.value
                val totalDistance = restoredDistance + counterDelta(startDistance, finalStatus.distance)
                val totalSteps = restoredSteps + if (finalStatus.hasStepCount) {
                    counterDelta(startSteps, finalStatus.steps)
                } else {
                    estimateStepsFromDistance(counterDelta(startDistance, finalStatus.distance))
                }
                val distanceMeters = totalDistance * 10.0
                val reportedCalories = restoredCalories + counterDelta(startCalories, finalStatus.calories)
                val averageHeartRate = if (activeHeartRateSampleCount > 0) {
                    (activeHeartRateTotal / activeHeartRateSampleCount).toInt()
                } else {
                    0
                }
                val sessionMaxHeartRate = maxHeartRate
                val sessionStats = SessionEntity(
                    startTime = start,
                    endTime = end,
                    totalDistance = totalDistance,
                    totalSteps = totalSteps,
                    totalEnergy = reportedCalories,
                    averageHeartRate = averageHeartRate,
                    maxHeartRate = sessionMaxHeartRate
                )
                sessionRepository.endSession(id, sessionStats)
                val sessionMetrics = sessionRepository.getMetricsForSessionSnapshot(id)

                // Write to Health Connect
                val wasWrittenToHealthConnect = healthConnectManager.writeSession(
                    startTime = start,
                    endTime = end,
                    steps = totalSteps,
                    distanceMeters = distanceMeters,
                    activeCaloriesKcal = reportedCalories,
                    speedSamples = sessionMetrics.toHealthConnectSpeedSamples()
                )
                if (wasWrittenToHealthConnect) {
                    finishHealthConnectSync()
                }
                sessionRepository.deleteCheckpoint(id)
                settingsRepository.requestBackupIfEnabled()
                resetInMemorySessionState()
                sessionJob = null

                Timber.d("Session stopped and saved")
            } finally {
                _isSessionFinalizing.value = false
            }
        }
    }

    fun finishRecoveredSession() {
        val recovered = _recoverableSession.value ?: return
        if (!_isSessionFinalizing.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                val checkpoint = recovered.checkpoint
                val completedSession = recovered.toCompletedSession()
                val end = completedSession.endTime!!
                sessionRepository.endSession(
                    checkpoint.sessionId,
                    completedSession
                )
                val metrics = sessionRepository.getMetricsForSessionSnapshot(checkpoint.sessionId)
                val wasWrittenToHealthConnect = healthConnectManager.writeSession(
                    startTime = recovered.session.startTime,
                    endTime = end,
                    steps = checkpoint.totalSteps,
                    distanceMeters = checkpoint.totalDistance * 10.0,
                    activeCaloriesKcal = checkpoint.totalEnergy,
                    speedSamples = metrics.toHealthConnectSpeedSamples()
                )
                if (wasWrittenToHealthConnect) {
                    finishHealthConnectSync()
                }
                sessionRepository.deleteCheckpoint(checkpoint.sessionId)
                _recoverableSession.value = null
                settingsRepository.requestBackupIfEnabled()
                Timber.d("Recovered session finished and saved")
            } finally {
                _isSessionFinalizing.value = false
            }
        }
    }

    fun discardRecoveredSession() {
        val sessionId = _recoverableSession.value?.checkpoint?.sessionId ?: return
        scope.launch {
            sessionRepository.discardUnfinishedSession(sessionId)
            _recoverableSession.value = null
            settingsRepository.requestBackupIfEnabled()
        }
    }

    private suspend fun finishHealthConnectSync() {
        _healthConnectSessionWrites.tryEmit(Unit)
        try {
            healthConnectHistoryRefresher.refreshSinceLastFetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Unable to refresh Health Connect history after session export")
        }
        openGarminConnect()
    }

    private fun openGarminConnect() {
        context.openGarminConnect()
    }

    private fun launchSession(recovered: RecoverableSession?) {
        if (sessionJob?.isActive == true) {
            _isSessionStarting.value = false
            return
        }

        val serviceIntent = Intent(context, dev.whileloop.c3p0.service.SessionService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to start session service without required permissions")
            _isSessionStarting.value = false
            return
        }

        sessionJob = scope.launch {
            try {
                val startStatus = treadmillManager.status.value
                if (!treadmillManager.start()) {
                    context.stopService(serviceIntent)
                    sessionJob = null
                    _isSessionStarting.value = false
                    return@launch
                }

                val now = Instant.now()
                val checkpoint = recovered?.checkpoint
                startTime = recovered?.session?.startTime ?: now
                currentSessionId = checkpoint?.sessionId ?: sessionRepository.startSession(now)
                restoredElapsedSeconds = checkpoint?.elapsedSeconds ?: 0
                restoredDistance = checkpoint?.totalDistance ?: 0
                restoredSteps = checkpoint?.totalSteps ?: 0
                restoredCalories = checkpoint?.totalEnergy ?: 0
                activeHeartRateTotal = checkpoint?.heartRateTotal ?: 0L
                activeHeartRateSampleCount = checkpoint?.heartRateSampleCount ?: 0
                maxHeartRate = checkpoint?.maxHeartRate ?: 0
                activeStartedAt = now
                accumulatedActiveDuration = Duration.ZERO
                startDistance = startStatus.distance
                startSteps = startStatus.steps
                startCalories = startStatus.calories
                startPadTime = startStatus.time
                lastCheckpointAt = null
                _isSessionActive.value = true
                _isSessionStarting.value = false
                _isSessionPaused.value = false
                sessionSpeedCommandGate.allow()
                _recoverableSession.value = null
                saveCheckpoint(now, wasPaused = false)

                launch {
                    while (isActive) {
                        delay(SESSION_CHECKPOINT_INTERVAL_MS)
                        saveCheckpoint(Instant.now(), wasPaused = _isSessionPaused.value)
                    }
                }

                Timber.d(if (recovered == null) "Session started: $currentSessionId" else "Session resumed: $currentSessionId")

                var previousMetricSteps = startStatus.steps
                var previousMetricTime = now
                combine(treadmillManager.status, heartRateManager.heartRate) { status, hr -> status to hr }
                    .collect { (status, hr) ->
                        val sampleTime = Instant.now()
                        if (_isSessionPaused.value) {
                            previousMetricSteps = status.steps
                            previousMetricTime = sampleTime
                            return@collect
                        }

                        val metric = SessionMetricEntity(
                            sessionId = currentSessionId!!,
                            timestamp = sampleTime,
                            heartRate = hr.takeIf { it > 0 },
                            speed = status.speed,
                            cadence = calculateCadence(
                                previousMetricSteps,
                                status.steps,
                                previousMetricTime,
                                sampleTime
                            )
                        )
                        previousMetricSteps = status.steps
                        previousMetricTime = sampleTime
                        sessionRepository.addMetric(metric)

                        metric.heartRate?.let { heartRate ->
                            activeHeartRateTotal += heartRate
                            activeHeartRateSampleCount += 1
                            maxHeartRate = maxOf(maxHeartRate, heartRate)
                        }
                        if (_isAutoSpeedEnabled.value && metric.heartRate != null) {
                            autoSpeedController?.addHrSample(
                                hr = metric.heartRate,
                                speedKmh = metric.speed ?: status.speed,
                                timestamp = System.currentTimeMillis()
                            )
                        }

                        settingsRepository.requestBackupIfEnabled(SESSION_BACKUP_REQUEST_INTERVAL_MS)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Session failed while starting or collecting metrics")
                sessionSpeedCommandGate.block()
                treadmillManager.pause()
                runCatching { saveCheckpoint(Instant.now(), wasPaused = true) }
                    .onFailure { Timber.e(it, "Unable to save session recovery checkpoint") }
                context.stopService(serviceIntent)
                resetInMemorySessionState()
                sessionJob = null
                refreshRecoverableSession()
            }
        }
    }

    private suspend fun saveCheckpoint(now: Instant, wasPaused: Boolean) {
        checkpointMutex.withLock {
            val id = currentSessionId ?: return@withLock
            if (lastCheckpointAt?.isAfter(now) == true) return@withLock
            val status = treadmillManager.status.value
            val distanceDelta = counterDelta(startDistance, status.distance)
            val checkpoint = ActiveSessionCheckpointEntity(
                sessionId = id,
                checkpointTime = now,
                elapsedSeconds = restoredElapsedSeconds + counterDelta(startPadTime, status.time),
                totalDistance = restoredDistance + distanceDelta,
                totalSteps = restoredSteps + if (status.hasStepCount) {
                    counterDelta(startSteps, status.steps)
                } else {
                    estimateStepsFromDistance(distanceDelta)
                },
                totalEnergy = restoredCalories + counterDelta(startCalories, status.calories),
                heartRateTotal = activeHeartRateTotal,
                heartRateSampleCount = activeHeartRateSampleCount,
                maxHeartRate = maxHeartRate,
                wasPaused = wasPaused
            )
            sessionRepository.saveCheckpoint(checkpoint)
            lastCheckpointAt = now
        }
    }

    private suspend fun refreshRecoverableSession() {
        val checkpoint = sessionRepository.getRecoverableCheckpoint()
        val session = checkpoint?.let { sessionRepository.getSession(it.sessionId) }
        val recovered = if (checkpoint != null && session != null) {
            RecoverableSession(session, checkpoint)
        } else {
            null
        }
        if (!_isSessionActive.value) {
            _recoverableSession.value = recovered
        }
    }

    fun enableAutoSpeed(targetHr: Int, zoneMinHr: Int, zoneMaxHr: Int) {
        val maxSpeedKmh = zone2MaxSpeedKmh.value.coerceIn(AUTO_SPEED_MIN_KMH, AUTO_SPEED_MAX_KMH)
        autoSpeedController = AutoSpeedController(
            targetHr = targetHr,
            zoneMinHr = zoneMinHr,
            zoneMaxHr = zoneMaxHr,
            maxSpeed = maxSpeedKmh
        ).apply {
            onSpeedAdjustmentRequired = { adjustment ->
                scope.launch {
                    val liveMaxSpeedKmh = zone2MaxSpeedKmh.value
                        .coerceIn(AUTO_SPEED_MIN_KMH, AUTO_SPEED_MAX_KMH)
                    val currentSpeed = treadmillManager.status.value.speed
                    treadmillManager.setSpeed(
                        (currentSpeed + adjustment).coerceIn(AUTO_SPEED_MIN_KMH, liveMaxSpeedKmh)
                    )
                }
            }
        }
        _isAutoSpeedEnabled.value = true
    }

    fun disableAutoSpeed() {
        _isAutoSpeedEnabled.value = false
        autoSpeedController = null
    }

    private fun resetInMemorySessionState() {
        sessionSpeedCommandGate.block()
        _isSessionStarting.value = false
        _isSessionActive.value = false
        _isSessionPaused.value = false
        disableAutoSpeed()
        currentSessionId = null
        startTime = null
        activeStartedAt = null
        accumulatedActiveDuration = Duration.ZERO
        startPadTime = 0
        activeHeartRateTotal = 0
        activeHeartRateSampleCount = 0
        maxHeartRate = 0
        restoredElapsedSeconds = 0
        restoredDistance = 0
        restoredSteps = 0
        restoredCalories = 0
        lastCheckpointAt = null
    }

    private fun accumulateActiveDuration(end: Instant = Instant.now()) {
        val activeStart = activeStartedAt ?: return
        if (end.isAfter(activeStart)) {
            accumulatedActiveDuration = accumulatedActiveDuration.plus(Duration.between(activeStart, end))
        }
        activeStartedAt = null
    }

    private fun counterDelta(start: Int, end: Int): Int =
        if (end >= start) end - start else end

    private fun estimateStepsFromDistance(distanceDelta: Int): Int =
        ((distanceDelta * 10.0) / ESTIMATED_STRIDE_LENGTH_METERS).roundToInt().coerceAtLeast(0)

    private fun calculateCadence(previousSteps: Int, currentSteps: Int, previousTime: Instant, currentTime: Instant): Int {
        val elapsedMinutes = Duration.between(previousTime, currentTime).toMillis() / 60_000.0
        if (elapsedMinutes <= 0.0) return 0

        val stepDelta = counterDelta(previousSteps, currentSteps)
        return (stepDelta / elapsedMinutes).roundToInt().coerceAtLeast(0)
    }

    companion object {
        private const val SESSION_CHECKPOINT_INTERVAL_MS = 60 * 1000L
        private const val HEART_RATE_FRESHNESS_WINDOW_MS = 5_000L
        private const val HEART_RATE_ZONE2_GUARD_INTERVAL_MS = 1_000L
        private const val SESSION_BACKUP_REQUEST_INTERVAL_MS = 5 * 60 * 1000L
        private const val ESTIMATED_STRIDE_LENGTH_METERS = 0.75
        private const val AUTO_SPEED_MIN_KMH = 1.60934f
        private const val AUTO_SPEED_MAX_KMH = 6.0f
        private const val DEFAULT_ZONE2_MAX_SPEED_KMH = 3.5f * 1.60934f
    }
}

internal fun isHeartRateFresh(
    heartRate: Int,
    lastReceivedAtMillis: Long,
    nowElapsedMillis: Long,
    freshnessWindowMillis: Long = 5_000L
): Boolean =
    heartRate > 0 &&
        lastReceivedAtMillis > 0L &&
        nowElapsedMillis - lastReceivedAtMillis <= freshnessWindowMillis

data class RecoverableSession(
    val session: SessionEntity,
    val checkpoint: ActiveSessionCheckpointEntity
)

private fun Instant.coerceAfter(start: Instant): Instant =
    if (isAfter(start)) this else start.plusSeconds(1)

internal fun RecoverableSession.toCompletedSession(): SessionEntity {
    val averageHeartRate = if (checkpoint.heartRateSampleCount > 0) {
        (checkpoint.heartRateTotal / checkpoint.heartRateSampleCount).toInt()
    } else {
        0
    }
    return SessionEntity(
        startTime = session.startTime,
        endTime = checkpoint.checkpointTime.coerceAfter(session.startTime),
        totalDistance = checkpoint.totalDistance,
        totalSteps = checkpoint.totalSteps,
        totalEnergy = checkpoint.totalEnergy,
        averageHeartRate = averageHeartRate,
        maxHeartRate = checkpoint.maxHeartRate
    )
}

private fun List<SessionMetricEntity>.toHealthConnectSpeedSamples(): List<HealthConnectSpeedSample> =
    mapNotNull { metric ->
        metric.speed?.takeIf { it >= 0f }?.let { speedKmh ->
            HealthConnectSpeedSample(
                time = metric.timestamp,
                speedKmh = speedKmh.toDouble()
            )
        }
    }
