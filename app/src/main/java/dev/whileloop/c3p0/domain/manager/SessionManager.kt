package dev.whileloop.c3p0.domain.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import dev.whileloop.c3p0.ble.manager.*
import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.entity.SessionMetricEntity
import dev.whileloop.c3p0.data.repository.SessionRepository
import dev.whileloop.c3p0.data.repository.SettingsRepository
import dev.whileloop.c3p0.domain.algorithm.AutoSpeedController
import dev.whileloop.c3p0.health.HealthConnectManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
    private val healthConnectManager: HealthConnectManager
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
    private var activeHeartRateTotal = 0
    private var activeHeartRateSampleCount = 0
    private var maxHeartRate = 0

    private val _isAutoSpeedEnabled = MutableStateFlow(false)
    val isAutoSpeedEnabled = _isAutoSpeedEnabled.asStateFlow()

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive = _isSessionActive.asStateFlow()

    private val _isSessionPaused = MutableStateFlow(false)
    val isSessionPaused = _isSessionPaused.asStateFlow()

    private val zone2MaxSpeedKmh = settingsRepository.zone2MaxSpeedKmh.stateIn(
        scope,
        SharingStarted.Eagerly,
        DEFAULT_ZONE2_MAX_SPEED_KMH
    )

    private var autoSpeedController: AutoSpeedController? = null

    fun startSession() {
        if (sessionJob?.isActive == true) return

        // Start Foreground Service
        val intent = Intent(context, dev.whileloop.c3p0.service.SessionService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to start session service without required permissions")
            return
        }

        sessionJob = scope.launch {
            try {
                val startStatus = treadmillManager.status.value
                val started = treadmillManager.start()
                if (!started) {
                    context.stopService(Intent(context, dev.whileloop.c3p0.service.SessionService::class.java))
                    sessionJob = null
                    return@launch
                }

                startTime = Instant.now()
                activeStartedAt = startTime
                accumulatedActiveDuration = Duration.ZERO
                startDistance = startStatus.distance
                startSteps = startStatus.steps
                startCalories = startStatus.calories
                startPadTime = startStatus.time
                activeHeartRateTotal = 0
                activeHeartRateSampleCount = 0
                maxHeartRate = 0
                currentSessionId = sessionRepository.startSession()
                _isSessionActive.value = true
                _isSessionPaused.value = false

                Timber.d("Session started: $currentSessionId")

                var previousMetricSteps = startStatus.steps
                var previousMetricTime = Instant.now()

                // Collect data and save metrics
                combine(
                    treadmillManager.status,
                    heartRateManager.heartRate
                ) { status, hr -> status to hr }
                    .collect { (status, hr) ->
                        val now = Instant.now()
                        if (_isSessionPaused.value) {
                            previousMetricSteps = status.steps
                            previousMetricTime = now
                            return@collect
                        }

                        val cadence = calculateCadence(previousMetricSteps, status.steps, previousMetricTime, now)
                        previousMetricSteps = status.steps
                        previousMetricTime = now

                        val metric = SessionMetricEntity(
                            sessionId = currentSessionId!!,
                            timestamp = now,
                            heartRate = hr.takeIf { it > 0 },
                            speed = status.speed,
                            cadence = cadence
                        )

                        sessionRepository.addMetric(metric)
                        metric.heartRate
                            ?.takeIf { it > 0 }
                            ?.let { heartRate ->
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
                treadmillManager.pause()
                context.stopService(Intent(context, dev.whileloop.c3p0.service.SessionService::class.java))
                endCurrentSessionAfterFailure()
                resetInMemorySessionState()
                sessionJob = null
            }
        }
    }

    fun pauseSession() {
        if (_isSessionActive.value.not() || _isSessionPaused.value) return

        scope.launch {
            if (treadmillManager.pause()) {
                accumulateActiveDuration()
                _isSessionPaused.value = true
            }
        }
    }

    fun resumeSession() {
        if (_isSessionActive.value.not() || _isSessionPaused.value.not()) return

        scope.launch {
            if (treadmillManager.start()) {
                activeStartedAt = Instant.now()
                _isSessionPaused.value = false
            }
        }
    }

    fun stopSession() {
        scope.launch {
            if (!treadmillManager.stop()) {
                return@launch
            }

            sessionJob?.cancel()
            _isSessionActive.value = false
            _isSessionPaused.value = false
            disableAutoSpeed()

            context.stopService(Intent(context, dev.whileloop.c3p0.service.SessionService::class.java))

            val id = currentSessionId ?: return@launch
            val start = startTime ?: return@launch
            val end = Instant.now()
            accumulateActiveDuration(end)
            val finalStatus = treadmillManager.status.value
            val padActiveDuration = Duration.ofSeconds(counterDelta(startPadTime, finalStatus.time).toLong())
            val activeDuration = padActiveDuration.takeIf { !it.isZero && !it.isNegative }
                ?: accumulatedActiveDuration
            val totalDistance = counterDelta(startDistance, finalStatus.distance)
            val totalSteps = if (finalStatus.hasStepCount) {
                counterDelta(startSteps, finalStatus.steps)
            } else {
                estimateStepsFromDistance(totalDistance)
            }
            val distanceMeters = totalDistance * 10.0
            val reportedCalories = counterDelta(startCalories, finalStatus.calories)
            val averageHeartRate = if (activeHeartRateSampleCount > 0) {
                activeHeartRateTotal / activeHeartRateSampleCount
            } else {
                0
            }
            val sessionMaxHeartRate = maxHeartRate
            currentSessionId = null
            startTime = null
            activeStartedAt = null
            accumulatedActiveDuration = Duration.ZERO
            startPadTime = 0

            val sessionStats = SessionEntity(
                startTime = start,
                endTime = end,
                totalDistance = totalDistance,
                totalSteps = totalSteps,
                totalEnergy = if (finalStatus.calories > 0 || startCalories > 0) {
                    reportedCalories
                } else {
                    estimateCalories(distanceMeters, activeDuration, settingsRepository.bodyWeightKg.first())
                },
                averageHeartRate = averageHeartRate,
                maxHeartRate = sessionMaxHeartRate
            )
            startCalories = 0
            sessionRepository.endSession(id, sessionStats)
            
            // Write to Health Connect
            healthConnectManager.writeSession(start, end, totalSteps, distanceMeters)
            settingsRepository.requestBackupIfEnabled()

            Timber.d("Session stopped and saved")
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
                    val currentSpeed = treadmillManager.status.value.speed
                    treadmillManager.setSpeed(
                        (currentSpeed + adjustment).coerceIn(AUTO_SPEED_MIN_KMH, maxSpeedKmh)
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
    }

    private suspend fun endCurrentSessionAfterFailure() {
        val id = currentSessionId ?: return
        val start = startTime ?: return
        val end = Instant.now()
        val finalStatus = treadmillManager.status.value
        val averageHeartRate = if (activeHeartRateSampleCount > 0) {
            activeHeartRateTotal / activeHeartRateSampleCount
        } else {
            0
        }
        val sessionStats = SessionEntity(
            startTime = start,
            endTime = end,
            totalDistance = counterDelta(startDistance, finalStatus.distance),
            totalSteps = if (finalStatus.hasStepCount) {
                counterDelta(startSteps, finalStatus.steps)
            } else {
                0
            },
            totalEnergy = counterDelta(startCalories, finalStatus.calories),
            averageHeartRate = averageHeartRate,
            maxHeartRate = maxHeartRate
        )
        runCatching {
            sessionRepository.endSession(id, sessionStats)
            settingsRepository.requestBackupIfEnabled()
        }.onFailure { failure ->
            Timber.e(failure, "Failed to close session after runtime failure")
        }
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

    private fun estimateCalories(distanceMeters: Double, activeDuration: Duration, bodyWeightKg: Double?): Int {
        val activeHours = activeDuration.toMillis() / 3_600_000.0
        if (activeHours <= 0.0) return 0

        val averageSpeedKmh = (distanceMeters / 1000.0) / activeHours
        val met = when {
            averageSpeedKmh < 1.0 -> 1.8
            averageSpeedKmh < 3.2 -> 2.8
            averageSpeedKmh < 4.8 -> 3.5
            averageSpeedKmh < 5.6 -> 4.3
            averageSpeedKmh < 6.4 -> 5.0
            else -> 6.3
        }
        return (met * (bodyWeightKg ?: DEFAULT_BODY_WEIGHT_KG) * activeHours).roundToInt()
    }

    private fun calculateCadence(previousSteps: Int, currentSteps: Int, previousTime: Instant, currentTime: Instant): Int {
        val elapsedMinutes = Duration.between(previousTime, currentTime).toMillis() / 60_000.0
        if (elapsedMinutes <= 0.0) return 0

        val stepDelta = counterDelta(previousSteps, currentSteps)
        return (stepDelta / elapsedMinutes).roundToInt().coerceAtLeast(0)
    }

    companion object {
        private const val SESSION_BACKUP_REQUEST_INTERVAL_MS = 5 * 60 * 1000L
        private const val DEFAULT_BODY_WEIGHT_KG = 70.0
        private const val ESTIMATED_STRIDE_LENGTH_METERS = 0.75
        private const val AUTO_SPEED_MIN_KMH = 1.60934f
        private const val AUTO_SPEED_MAX_KMH = 6.0f
        private const val DEFAULT_ZONE2_MAX_SPEED_KMH = 3.5f * 1.60934f
    }
}
