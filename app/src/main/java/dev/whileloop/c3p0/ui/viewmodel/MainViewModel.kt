package dev.whileloop.c3p0.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whileloop.c3p0.data.repository.SettingsRepository
import dev.whileloop.c3p0.health.HealthConnectManager
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val healthConnectManager: HealthConnectManager,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    fun refreshWeightFromHealthConnect() {
        viewModelScope.launch {
            healthConnectManager.readLatestWeightKg()?.let { weightKg ->
                settingsRepository.saveBodyWeightKg(weightKg)
            }
        }
    }
}
