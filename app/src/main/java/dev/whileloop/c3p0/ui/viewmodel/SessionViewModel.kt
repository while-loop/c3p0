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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    private var elapsedJob: Job? = null
    private var heartRateHistoryJob: Job? = null

    private val _sessionElapsedSeconds = MutableStateFlow(0)
    val sessionElapsedSeconds = _sessionElapsedSeconds.asStateFlow()

    private val _heartRateHistory = MutableStateFlow<List<Int>>(emptyList())
    val heartRateHistory = _heartRateHistory.asStateFlow()

    private val _averageHeartRate = MutableStateFlow(0)
    val averageHeartRate = _averageHeartRate.asStateFlow()

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

    val isSessionActive = sessionManager.isSessionActive.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        sessionManager.isSessionActive.value
    )

    val unitSystem = settingsRepository.unitSystem.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UnitSystem.Metric
    )

    val skipInactiveDeviceWarning = settingsRepository.skipInactiveDeviceWarning.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
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
            sessionManager.isSessionActive.collect { isActive ->
                if (isActive) {
                    startSessionStats()
                } else {
                    stopSessionStats()
                }
            }
        }
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

    fun updateSkipInactiveDeviceWarning(skip: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveSkipInactiveDeviceWarning(skip)
        }
    }

    fun incrementSpeed() {
        viewModelScope.launch {
            val current = treadmillStatus.value.speed
            treadmillManager.setSpeed(current + 0.1f)
        }
    }

    fun decrementSpeed() {
        viewModelScope.launch {
            val current = treadmillStatus.value.speed
            treadmillManager.setSpeed((current - 0.1f).coerceAtLeast(0f))
        }
    }

    fun setMode(mode: TreadmillMode) {
        viewModelScope.launch {
            treadmillManager.setMode(mode)
        }
    }

    private fun startSessionStats() {
        _sessionElapsedSeconds.value = 0
        _heartRateHistory.value = emptyList()
        _averageHeartRate.value = 0

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
                if (heartRate <= 0) return@collect

                val updated = (_heartRateHistory.value + heartRate).takeLast(MAX_HEART_RATE_SAMPLES)
                _heartRateHistory.value = updated
                _averageHeartRate.value = updated.average().toInt()
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
    }
}
