package dev.whileloop.c3p0.ble.controller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import dev.whileloop.c3p0.ble.model.BleDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleScanner @Inject constructor(
    private val context: Context
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter

    @SuppressLint("MissingPermission")
    fun scan(): Flow<BleDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Timber.e("BluetoothLeScanner is null")
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                trySend(BleDevice(device.name, device.address, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.e("Scan failed with error code: $errorCode")
                close()
            }
        }

        Timber.d("Starting BLE scan")
        scanner.startScan(callback)

        awaitClose {
            Timber.d("Stopping BLE scan")
            scanner.stopScan(callback)
        }
    }
}
