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
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

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

    private val _isAutoSpeedEnabled = MutableStateFlow(false)
    val isAutoSpeedEnabled = _isAutoSpeedEnabled.asStateFlow()

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive = _isSessionActive.asStateFlow()

    private val _isSessionPaused = MutableStateFlow(false)
    val isSessionPaused = _isSessionPaused.asStateFlow()

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

        _isSessionActive.value = true
        _isSessionPaused.value = false

        sessionJob = scope.launch {
            startTime = Instant.now()
            currentSessionId = sessionRepository.startSession()
            
            Timber.d("Session started: $currentSessionId")

            // Collect data and save metrics
            combine(
                treadmillManager.status,
                heartRateManager.heartRate
            ) { status, hr ->
                SessionMetricEntity(
                    sessionId = currentSessionId!!,
                    timestamp = Instant.now(),
                    heartRate = hr,
                    speed = status.speed,
                    cadence = (status.speed * 1.5).toInt() // mock cadence
                )
            }.collect { metric ->
                if (_isSessionPaused.value) return@collect

                sessionRepository.addMetric(metric)
                
                if (_isAutoSpeedEnabled.value) {
                    autoSpeedController?.addHrSample(metric.heartRate ?: 0, System.currentTimeMillis())
                }
            }
        }
    }

    fun pauseSession() {
        if (_isSessionActive.value.not() || _isSessionPaused.value) return

        scope.launch {
            treadmillManager.stop()
            _isSessionPaused.value = true
        }
    }

    fun resumeSession() {
        if (_isSessionActive.value.not() || _isSessionPaused.value.not()) return

        scope.launch {
            treadmillManager.start()
            _isSessionPaused.value = false
        }
    }

    fun stopSession() {
        sessionJob?.cancel()
        _isSessionActive.value = false
        _isSessionPaused.value = false
        
        // Stop Foreground Service
        context.stopService(Intent(context, dev.whileloop.c3p0.service.SessionService::class.java))

        val id = currentSessionId ?: return
        val start = startTime ?: return
        val end = Instant.now()

        scope.launch {
            val finalStatus = treadmillManager.status.value
            val sessionStats = SessionEntity(
                startTime = start,
                endTime = end,
                totalDistance = finalStatus.distance,
                totalSteps = finalStatus.steps,
                totalEnergy = 0 // to be calculated
            )
            sessionRepository.endSession(id, sessionStats)
            
            // Write to Health Connect
            healthConnectManager.writeSession(start, end, finalStatus.steps, finalStatus.distance * 10.0)
            settingsRepository.requestBackupIfEnabled()
            
            currentSessionId = null
            startTime = null
            Timber.d("Session stopped and saved")
        }
    }

    fun enableAutoSpeed(targetHr: Int) {
        autoSpeedController = AutoSpeedController(targetHr).apply {
            onSpeedAdjustmentRequired = { adjustment ->
                scope.launch {
                    val currentSpeed = treadmillManager.status.value.speed
                    treadmillManager.setSpeed(currentSpeed + adjustment)
                }
            }
        }
        _isAutoSpeedEnabled.value = true
    }

    fun disableAutoSpeed() {
        _isAutoSpeedEnabled.value = false
        autoSpeedController = null
    }
}
