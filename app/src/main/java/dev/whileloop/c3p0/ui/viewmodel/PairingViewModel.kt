package dev.whileloop.c3p0.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.whileloop.c3p0.ble.controller.BleScanner
import dev.whileloop.c3p0.ble.model.BleDevice
import dev.whileloop.c3p0.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PairingViewModel @Inject constructor(
    val scanner: BleScanner,
    val settingsRepository: SettingsRepository
) : ViewModel() {
    private val _devices = MutableStateFlow(setOf<BleDevice>())
    val devices = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    fun startScan() {
        if (_isScanning.value) return

        _isScanning.value = true
        viewModelScope.launch {
            try {
                scanner.scan().collect { device ->
                    _devices.value = _devices.value + device
                }
            } catch (e: SecurityException) {
                Timber.e(e, "BLE scan failed because permission was denied")
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun pairDevice(device: BleDevice) {
        viewModelScope.launch {
            if (device.name?.contains("WalkingPad", ignoreCase = true) == true) {
                settingsRepository.saveTreadmillAddress(device.address)
            } else {
                settingsRepository.saveWatchAddress(device.address)
            }
        }
    }
}
