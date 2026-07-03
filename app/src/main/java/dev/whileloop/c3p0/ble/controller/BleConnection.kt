package dev.whileloop.c3p0.ble.controller

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
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
    }

    var onNotificationReceived: ((UUID, ByteArray) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, data: ByteArray): Boolean {
        val service = bluetoothGatt?.getService(serviceUuid) ?: return false
        val char = service.getCharacteristic(charUuid) ?: return false
        char.value = data
        return bluetoothGatt?.writeCharacteristic(char) ?: false
    }

    @SuppressLint("MissingPermission")
    fun enableNotifications(serviceUuid: UUID, charUuid: UUID): Boolean {
        val service = bluetoothGatt?.getService(serviceUuid) ?: return false
        val char = service.getCharacteristic(charUuid) ?: return false
        bluetoothGatt?.setCharacteristicNotification(char, true) ?: return false
        
        val descriptor = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) ?: return false
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return bluetoothGatt?.writeDescriptor(descriptor) ?: false
    }

    @SuppressLint("MissingPermission")
    fun connect(): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = adapter.getRemoteDevice(address) ?: return false

        Timber.d("Connecting to $address")
        _connectionState.value = ConnectionState.CONNECTING
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        return true
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTING
        bluetoothGatt?.disconnect()
    }

    @SuppressLint("MissingPermission")
    fun close() {
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}
