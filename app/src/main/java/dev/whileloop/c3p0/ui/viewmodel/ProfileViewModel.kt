package dev.whileloop.c3p0.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whileloop.c3p0.ble.manager.TreadmillManager
import dev.whileloop.c3p0.data.model.UnitSystem
import dev.whileloop.c3p0.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val treadmillManager: TreadmillManager
) : ViewModel() {
    private val _stepGoal = MutableStateFlow(10000)
    val stepGoal = _stepGoal.asStateFlow()

    private val _age = MutableStateFlow(30)
    val age = _age.asStateFlow()

    private val _isHealthConnectEnabled = MutableStateFlow(false)
    val isHealthConnectEnabled = _isHealthConnectEnabled.asStateFlow()

    private val _isGoogleDriveSyncEnabled = MutableStateFlow(false)
    val isGoogleDriveSyncEnabled = _isGoogleDriveSyncEnabled.asStateFlow()

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

    fun updateStepGoal(goal: Int) {
        _stepGoal.value = goal
    }

    fun updateAge(age: Int) {
        _age.value = age
    }

    fun toggleHealthConnect(enabled: Boolean) {
        _isHealthConnectEnabled.value = enabled
    }

    fun toggleGoogleDriveSync(enabled: Boolean) {
        _isGoogleDriveSyncEnabled.value = enabled
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
}
