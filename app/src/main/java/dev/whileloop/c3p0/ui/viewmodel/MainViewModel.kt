package dev.whileloop.c3p0.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.whileloop.c3p0.ble.diagnostic.BleErrorReporter
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val bleErrorReporter: BleErrorReporter
) : ViewModel() {
    val bluetoothErrors = bleErrorReporter.errors

    fun dismissBluetoothError(id: Long) {
        bleErrorReporter.dismiss(id)
    }

    fun clearBluetoothErrors() {
        bleErrorReporter.clear()
    }
}
