package dev.whileloop.c3p0.ble.controller

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.whileloop.c3p0.ble.manager.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.*

class BleConnection(
    private val context: Context,
    private val address: String
) {
    private var bluetoothGatt: BluetoothGatt? = null
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
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
                Timber.d("Services discovered for $address")
                onServicesDiscovered?.invoke()
            } else {
                Timber.w("onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // To be implemented by subclasses/delegates
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

    @SuppressLint("MissingPermission")
    fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, data: ByteArray): Boolean {
        if (!hasConnectPermission()) return false
        val service = bluetoothGatt?.getService(serviceUuid) ?: return false
        val char = service.getCharacteristic(charUuid) ?: return false
        char.value = data
        return try {
            bluetoothGatt?.writeCharacteristic(char) ?: false
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to write BLE characteristic without permission")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun enableNotifications(serviceUuid: UUID, charUuid: UUID): Boolean {
        if (!hasConnectPermission()) return false
        val service = bluetoothGatt?.getService(serviceUuid) ?: return false
        val char = service.getCharacteristic(charUuid) ?: return false
        try {
            bluetoothGatt?.setCharacteristicNotification(char, true) ?: return false
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to enable BLE notifications without permission")
            return false
        }
        
        val descriptor = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) ?: return false
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return try {
            bluetoothGatt?.writeDescriptor(descriptor) ?: false
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to write BLE descriptor without permission")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(): Boolean {
        if (!hasConnectPermission()) {
            Timber.w("BLE connect requested without required permissions")
            return false
        }

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = adapter.getRemoteDevice(address) ?: return false

        Timber.d("Connecting to $address")
        _connectionState.value = ConnectionState.CONNECTING
        return try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            true
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to connect BLE device without permission")
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
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        try {
            bluetoothGatt?.close()
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to close BLE device without permission")
        }
        bluetoothGatt = null
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
}
