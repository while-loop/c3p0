@file:Suppress("DEPRECATION")

package dev.whileloop.c3p0.ble.controller

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.whileloop.c3p0.ble.diagnostic.BleErrorReporter
import dev.whileloop.c3p0.ble.manager.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.*

class BleConnection(
    private val context: Context,
    private val address: String,
    private val errorReporter: BleErrorReporter
) {
    private var bluetoothGatt: BluetoothGatt? = null
    private var discoveredServiceSummary: String = "none"
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                reportError("GATT state change failed", "address=$address status=$status newState=$newState")
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.d("Connected to $address")
                    _connectionState.value = ConnectionState.CONNECTED
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.d("Disconnected from $address")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                discoveredServiceSummary = gatt.services
                    .joinToString(separator = ",") { service -> service.uuid.toString() }
                    .ifBlank { "empty" }
                Timber.d("Services discovered for $address")
                onServicesDiscovered?.invoke()
            } else {
                Timber.w("onServicesDiscovered received: $status")
                reportError("Service discovery failed", "address=$address status=$status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("Characteristic write failed for ${characteristic.uuid}: $status")
                reportError(
                    "Characteristic write failed",
                    "address=$address characteristic=${characteristic.uuid} status=$status"
                )
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("Descriptor write failed for ${descriptor.uuid}: $status")
                reportError(
                    "Descriptor write failed",
                    "address=$address descriptor=${descriptor.uuid} status=$status"
                )
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onNotificationReceived?.invoke(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onNotificationReceived?.invoke(characteristic.uuid, value)
        }
    }

    var onNotificationReceived: ((UUID, ByteArray) -> Unit)? = null
    var onServicesDiscovered: (() -> Unit)? = null

    fun serviceSummary(): String = discoveredServiceSummary

    @SuppressLint("MissingPermission")
    fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, data: ByteArray): Boolean {
        if (!hasConnectPermission()) {
            reportError("Missing Bluetooth connect permission", "write characteristic $charUuid")
            return false
        }
        val service = bluetoothGatt?.getService(serviceUuid) ?: run {
            reportError("BLE service not found", "address=$address service=$serviceUuid")
            return false
        }
        val char = service.getCharacteristic(charUuid) ?: run {
            reportError("BLE characteristic not found", "address=$address characteristic=$charUuid")
            return false
        }
        val writeType =
            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeCharacteristic(char, data, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                char.writeType = writeType
                char.value = data
                bluetoothGatt?.writeCharacteristic(char) ?: false
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to write BLE characteristic without permission")
            reportError("Unable to write BLE characteristic", e.message)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun enableNotifications(serviceUuid: UUID, charUuid: UUID): Boolean {
        if (!hasConnectPermission()) {
            reportError("Missing Bluetooth connect permission", "enable notifications for $charUuid")
            return false
        }
        val service = bluetoothGatt?.getService(serviceUuid) ?: run {
            reportError("BLE service not found", "address=$address service=$serviceUuid")
            return false
        }
        val char = service.getCharacteristic(charUuid) ?: run {
            reportError("BLE characteristic not found", "address=$address characteristic=$charUuid")
            return false
        }
        try {
            val notificationSet = bluetoothGatt?.setCharacteristicNotification(char, true) ?: false
            if (!notificationSet) {
                reportError("Unable to enable characteristic notification", "address=$address characteristic=$charUuid")
                return false
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to enable BLE notifications without permission")
            reportError("Unable to enable BLE notifications", e.message)
            return false
        }
        
        val descriptor = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: run {
            reportError("BLE notification descriptor not found", "address=$address characteristic=$charUuid")
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                bluetoothGatt?.writeDescriptor(descriptor) ?: false
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to write BLE descriptor without permission")
            reportError("Unable to write BLE descriptor", e.message)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(): Boolean {
        if (!hasConnectPermission()) {
            Timber.w("BLE connect requested without required permissions")
            reportError("Missing Bluetooth connect permission", "connect to $address")
            return false
        }

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Invalid BLE address: $address")
            reportError("Invalid BLE address", address)
            return false
        }

        Timber.d("Connecting to $address")
        _connectionState.value = ConnectionState.CONNECTING
        return try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            true
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to connect BLE device without permission")
            reportError("Unable to connect BLE device", e.message)
            _connectionState.value = ConnectionState.DISCONNECTED
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTING
        try {
            bluetoothGatt?.disconnect()
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to disconnect BLE device without permission")
            reportError("Unable to disconnect BLE device", e.message)
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        try {
            bluetoothGatt?.close()
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to close BLE device without permission")
            reportError("Unable to close BLE device", e.message)
        }
        bluetoothGatt = null
    }

    private fun reportError(message: String, detail: String? = null) {
        errorReporter.report(BLE_ERROR_SOURCE, message, detail)
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        private const val BLE_ERROR_SOURCE = "Bluetooth"
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
