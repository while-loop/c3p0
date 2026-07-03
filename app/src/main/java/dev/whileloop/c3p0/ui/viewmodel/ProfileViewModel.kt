package dev.whileloop.c3p0.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor() : ViewModel() {
    private val _stepGoal = MutableStateFlow(10000)
    val stepGoal = _stepGoal.asStateFlow()

    private val _age = MutableStateFlow(30)
    val age = _age.asStateFlow()

    private val _isHealthConnectEnabled = MutableStateFlow(false)
    val isHealthConnectEnabled = _isHealthConnectEnabled.asStateFlow()

    private val _isGoogleDriveSyncEnabled = MutableStateFlow(false)
    val isGoogleDriveSyncEnabled = _isGoogleDriveSyncEnabled.asStateFlow()

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
}
