package dev.whileloop.c3p0.ble.controller

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
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
        if (!hasScanPermission()) {
            Timber.w("BLE scan requested without required permissions")
            close()
            return@callbackFlow
        }

        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Timber.e("BluetoothLeScanner is null")
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = try {
                    device.name
                } catch (e: SecurityException) {
                    Timber.e(e, "Unable to read BLE device name without permission")
                    null
                }
                trySend(BleDevice(name, device.address, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.e("Scan failed with error code: $errorCode")
                close()
            }
        }

        Timber.d("Starting BLE scan")
        try {
            scanner.startScan(callback)
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to start BLE scan without permission")
            close(e)
            return@callbackFlow
        }

        awaitClose {
            Timber.d("Stopping BLE scan")
            try {
                scanner.stopScan(callback)
            } catch (e: SecurityException) {
                Timber.e(e, "Unable to stop BLE scan without permission")
            }
        }
    }

    private fun hasScanPermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}
