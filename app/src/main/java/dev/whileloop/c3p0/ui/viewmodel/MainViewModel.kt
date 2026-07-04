package dev.whileloop.c3p0.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whileloop.c3p0.ble.diagnostic.BleErrorReporter
import dev.whileloop.c3p0.data.repository.SettingsRepository
import dev.whileloop.c3p0.health.HealthConnectManager
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val healthConnectManager: HealthConnectManager,
    private val settingsRepository: SettingsRepository,
    private val bleErrorReporter: BleErrorReporter
) : ViewModel() {
    val bluetoothErrors = bleErrorReporter.errors

    fun refreshWeightFromHealthConnect() {
        viewModelScope.launch {
            healthConnectManager.readLatestWeightKg()?.let { weightKg ->
                settingsRepository.saveBodyWeightKg(weightKg)
            }
        }
    }

    fun dismissBluetoothError(id: Long) {
        bleErrorReporter.dismiss(id)
    }

    fun clearBluetoothErrors() {
        bleErrorReporter.clear()
    }
}
