package dev.whileloop.c3p0.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.whileloop.c3p0.ble.controller.BleScanner
import dev.whileloop.c3p0.ble.manager.ConnectionState
import dev.whileloop.c3p0.ble.manager.HeartRateManager
import dev.whileloop.c3p0.ble.manager.TreadmillManager
import dev.whileloop.c3p0.ble.model.BleDevice
import dev.whileloop.c3p0.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PairingViewModel @Inject constructor(
    val scanner: BleScanner,
    val settingsRepository: SettingsRepository,
    private val treadmillManager: TreadmillManager,
    private val heartRateManager: HeartRateManager
) : ViewModel() {
    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices = _devices.asStateFlow()
    private val devicesByAddress = linkedMapOf<String, BleDevice>()
    private val deviceDisplayOrder = mutableListOf<String>()
    private val scanStatsByAddress = mutableMapOf<String, ScanStats>()
    private var revealJob: Job? = null

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _pairingAddress = MutableStateFlow<String?>(null)
    val pairingAddress = _pairingAddress.asStateFlow()

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

    fun startScan() {
        if (_isScanning.value) return

        _isScanning.value = true
        startRevealTimer()
        viewModelScope.launch {
            try {
                scanner.scan().collect { device ->
                    addOrUpdateDevice(device)
                }
            } catch (e: SecurityException) {
                Timber.e(e, "BLE scan failed because permission was denied")
            } finally {
                _isScanning.value = false
                revealJob?.cancel()
                revealJob = null
                revealQualifiedDevices(maxDevices = Int.MAX_VALUE)
            }
        }
    }

    fun pairDevice(device: BleDevice) {
        viewModelScope.launch {
            _pairingAddress.value = device.address
            try {
                if (device.isWalkingPad()) {
                    settingsRepository.saveTreadmillAddress(device.address)
                    treadmillManager.connect(device.address)
                } else {
                    settingsRepository.saveWatchAddress(device.address)
                    heartRateManager.connect(device.address)
                }
            } finally {
                _pairingAddress.value = null
            }
        }
    }

    fun deviceConnectionLabel(device: BleDevice): String? {
        val isWalkingPad = device.isWalkingPad()
        val savedAddress = if (isWalkingPad) treadmillAddress.value else watchAddress.value
        if (savedAddress != device.address) return null

        val state = if (isWalkingPad) treadmillConnectionState.value else watchConnectionState.value
        return when {
            pairingAddress.value == device.address -> "Pairing..."
            state == ConnectionState.CONNECTED -> "Connected"
            state == ConnectionState.CONNECTING -> "Connecting..."
            else -> "Saved"
        }
    }

    private fun BleDevice.isWalkingPad(): Boolean =
        name?.contains("WalkingPad", ignoreCase = true) == true

    private fun addOrUpdateDevice(device: BleDevice) {
        val existing = devicesByAddress[device.address]
        val stats = scanStatsByAddress.getOrPut(device.address) { ScanStats() }
        stats.eventCount += 1
        stats.rssiTotal += device.rssi

        val updated = device.copy(name = device.name ?: existing?.name)

        devicesByAddress[device.address] = updated
        if (device.address in deviceDisplayOrder) {
            publishDevicesInDisplayOrder()
        }
    }

    private fun startRevealTimer() {
        revealJob?.cancel()
        revealJob = viewModelScope.launch {
            while (_isScanning.value) {
                delay(REVEAL_INTERVAL_MS)
                revealQualifiedDevices(maxDevices = 1)
            }
        }
    }

    private fun revealQualifiedDevices(maxDevices: Int) {
        val qualified = devicesByAddress.keys
            .filter { address ->
                address !in deviceDisplayOrder &&
                    (scanStatsByAddress[address]?.eventCount ?: 0) >= MIN_SCAN_EVENTS_BEFORE_DISPLAY
            }
            .sortedWith(
                compareByDescending<String> { scanStatsByAddress[it]?.eventCount ?: 0 }
                    .thenByDescending { scanStatsByAddress[it]?.averageRssi ?: Int.MIN_VALUE.toDouble() }
                    .thenBy { devicesByAddress[it]?.name ?: "" }
                    .thenBy { it }
            )
            .take(maxDevices)

        if (qualified.isEmpty()) return

        deviceDisplayOrder += qualified
        publishDevicesInDisplayOrder()
    }

    private fun publishDevicesInDisplayOrder() {
        _devices.value = deviceDisplayOrder.mapNotNull { devicesByAddress[it] }
    }

    companion object {
        private const val MIN_SCAN_EVENTS_BEFORE_DISPLAY = 3
        private const val REVEAL_INTERVAL_MS = 500L
    }

    private data class ScanStats(
        var eventCount: Int = 0,
        var rssiTotal: Int = 0
    ) {
        val averageRssi: Double
            get() = if (eventCount > 0) rssiTotal.toDouble() / eventCount else Int.MIN_VALUE.toDouble()
    }
}
