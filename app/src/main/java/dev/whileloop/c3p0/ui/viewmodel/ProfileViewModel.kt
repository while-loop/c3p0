package dev.whileloop.c3p0.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whileloop.c3p0.ble.manager.ConnectionState
import dev.whileloop.c3p0.ble.manager.HeartRateManager
import dev.whileloop.c3p0.ble.manager.TreadmillManager
import dev.whileloop.c3p0.data.model.SessionStartMode
import dev.whileloop.c3p0.data.model.UnitSystem
import dev.whileloop.c3p0.data.repository.SettingsRepository
import dev.whileloop.c3p0.health.HealthConnectManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val treadmillManager: TreadmillManager,
    private val heartRateManager: HeartRateManager,
    private val healthConnectManager: HealthConnectManager
) : ViewModel() {
    private val _isHealthConnectEnabled = MutableStateFlow(false)
    val isHealthConnectEnabled = _isHealthConnectEnabled.asStateFlow()

    private val _isRefreshingWeight = MutableStateFlow(false)
    val isRefreshingWeight = _isRefreshingWeight.asStateFlow()

    val unitSystem = settingsRepository.unitSystem.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UnitSystem.Imperial
    )

    val stepGoal = settingsRepository.stepGoal.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        10000
    )

    val age = settingsRepository.age.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        30
    )

    val sessionStartMode = settingsRepository.sessionStartMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SessionStartMode.Zone2
    )

    val zone2MaxSpeedKmh = settingsRepository.zone2MaxSpeedKmh.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DEFAULT_ZONE2_MAX_SPEED_KMH
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

    val noLoadStopEnabled = settingsRepository.noLoadStopEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    val noLoadStopTimeoutSeconds = settingsRepository.noLoadStopTimeoutSeconds.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        30
    )

    val isGoogleDriveSyncEnabled = settingsRepository.googleDriveSyncEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    val bodyWeightKg = settingsRepository.bodyWeightKg.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null
    )

    val treadmillAddress = settingsRepository.treadmillAddress.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null
    )

    val watchAddress = settingsRepository.watchAddress.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null
    )

    val treadmillConnectionState = treadmillManager.connectionState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        treadmillManager.connectionState.value
    )

    val watchConnectionState = heartRateManager.connectionState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        heartRateManager.connectionState.value
    )

    fun connectionLabel(state: ConnectionState): String =
        when (state) {
            ConnectionState.CONNECTED -> "Connected"
            ConnectionState.CONNECTING -> "Connecting"
            ConnectionState.DISCONNECTING -> "Disconnecting"
            ConnectionState.DISCONNECTED -> "Not connected"
        }

    init {
        refreshHealthConnectPermissionState()

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
    }

    fun updateStepGoal(goal: Int) {
        viewModelScope.launch {
            settingsRepository.saveStepGoal(goal)
        }
    }

    fun updateAge(age: Int) {
        viewModelScope.launch {
            settingsRepository.saveAge(age)
        }
    }

    fun updateSessionStartMode(mode: SessionStartMode) {
        viewModelScope.launch {
            settingsRepository.saveSessionStartMode(mode)
        }
    }

    fun updateZone2MaxSpeedKmh(maxSpeedKmh: Float) {
        viewModelScope.launch {
            settingsRepository.saveZone2MaxSpeedKmh(maxSpeedKmh)
        }
    }

    fun toggleHealthConnect(enabled: Boolean) {
        _isHealthConnectEnabled.value = enabled
    }

    fun refreshHealthConnectPermissionState() {
        viewModelScope.launch {
            _isHealthConnectEnabled.value = healthConnectManager.hasAllPermissions()
        }
    }

    fun toggleGoogleDriveSync(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveGoogleDriveSyncEnabled(enabled)
        }
    }

    fun updateUnitSystem(unitSystem: UnitSystem) {
        viewModelScope.launch {
            settingsRepository.saveUnitSystem(unitSystem)
            treadmillManager.setUnitSystem(unitSystem)
        }
    }

    fun updateSkipInactiveDeviceWarning(skip: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveSkipInactiveDeviceWarning(skip)
        }
    }

    fun updateKeepScreenOnDuringActiveSession(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveKeepScreenOnDuringActiveSession(enabled)
        }
    }

    fun updateNoLoadStop(enabled: Boolean, timeoutSeconds: Int) {
        viewModelScope.launch {
            settingsRepository.saveNoLoadStop(enabled, timeoutSeconds)
        }
    }

    fun refreshWeightFromHealthConnect() {
        viewModelScope.launch {
            _isRefreshingWeight.value = true
            healthConnectManager.readLatestWeightKg()?.let { weightKg ->
                settingsRepository.saveBodyWeightKg(weightKg)
            }
            _isRefreshingWeight.value = false
        }
    }

    private companion object {
        private const val DEFAULT_ZONE2_MAX_SPEED_KMH = 3.5f * 1.60934f
    }
}
