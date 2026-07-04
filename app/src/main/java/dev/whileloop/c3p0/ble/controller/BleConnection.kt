@file:Suppress("DEPRECATION")

package dev.whileloop.c3p0.ble.controller

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private val errorReporter: BleErrorReporter,
    private val requiredServiceUuid: UUID? = null,
    private val fallbackServiceUuids: Set<UUID> = emptySet(),
    private val refreshGattOnConnect: Boolean = false,
    private val preferWriteWithoutResponse: Boolean = false
) {
    private var bluetoothGatt: BluetoothGatt? = null
    private var discoveredServiceSummary: String = "none"
    private var serviceDiscoveryAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
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
                    serviceDiscoveryAttempts = 0
                    if (refreshGattOnConnect) {
                        val cacheRefreshed = refreshDeviceCache(gatt)
                        Timber.d("GATT cache refresh requested for $address: $cacheRefreshed")
                    }
                    scheduleServiceDiscovery(gatt, SERVICE_DISCOVERY_DELAY_MS)
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
                    .joinToString(separator = ";") { service -> service.toSummary() }
                    .ifBlank { "empty" }
                Timber.d("Services discovered for $address")
                val acceptableServices = buildSet {
                    requiredServiceUuid?.let { add(it) }
                    addAll(fallbackServiceUuids)
                }
                if (acceptableServices.isNotEmpty() && acceptableServices.none { gatt.getService(it) != null }) {
                    retryServiceDiscoveryOrReport(
                        gatt = gatt,
                        message = "Required BLE service not found",
                        detail = "address=$address services=${acceptableServices.joinToString()} discovered=$discoveredServiceSummary"
                    )
                    return
                }
                onServicesDiscovered?.invoke()
            } else {
                Timber.w("onServicesDiscovered received: $status")
                retryServiceDiscoveryOrReport(
                    gatt = gatt,
                    message = "Service discovery failed",
                    detail = "address=$address status=$status"
                )
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
    fun writeCharacteristic(
        serviceUuid: UUID,
        charUuid: UUID,
        data: ByteArray,
        preferWithoutResponse: Boolean = preferWriteWithoutResponse
    ): Boolean {
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
        val supportsWrite = char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val supportsWriteWithoutResponse =
            char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val writeType = when {
            preferWithoutResponse && supportsWriteWithoutResponse ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            supportsWrite -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            supportsWriteWithoutResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
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

    fun writeCharacteristicByUuidSubstring(
        charUuidSubstring: String,
        data: ByteArray,
        preferWithoutResponse: Boolean = preferWriteWithoutResponse
    ): Boolean {
        val (serviceUuid, charUuid) = findCharacteristicUuidBySubstring(charUuidSubstring) ?: run {
            reportError("BLE characteristic not found", "address=$address characteristic~=$charUuidSubstring")
            return false
        }
        return writeCharacteristic(serviceUuid, charUuid, data, preferWithoutResponse)
    }

    @SuppressLint("MissingPermission")
    fun enableNotifications(serviceUuid: UUID, charUuid: UUID): Boolean {
        return enableCharacteristicUpdates(
            serviceUuid = serviceUuid,
            charUuid = charUuid,
            descriptorValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            updateLabel = "notifications"
        )
    }

    @SuppressLint("MissingPermission")
    fun enableIndications(serviceUuid: UUID, charUuid: UUID): Boolean {
        return enableCharacteristicUpdates(
            serviceUuid = serviceUuid,
            charUuid = charUuid,
            descriptorValue = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
            updateLabel = "indications"
        )
    }

    fun hasService(serviceUuid: UUID): Boolean =
        bluetoothGatt?.getService(serviceUuid) != null

    fun hasCharacteristicUuidSubstring(charUuidSubstring: String): Boolean =
        findCharacteristicUuidBySubstring(charUuidSubstring) != null

    private fun findCharacteristicUuidBySubstring(charUuidSubstring: String): Pair<UUID, UUID>? {
        val needle = charUuidSubstring.lowercase(Locale.US)
        val services = bluetoothGatt?.services ?: return null
        services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                if (characteristic.uuid.toString().lowercase(Locale.US).contains(needle)) {
                    return service.uuid to characteristic.uuid
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun enableCharacteristicUpdates(
        serviceUuid: UUID,
        charUuid: UUID,
        descriptorValue: ByteArray,
        updateLabel: String
    ): Boolean {
        if (!hasConnectPermission()) {
            reportError("Missing Bluetooth connect permission", "enable $updateLabel for $charUuid")
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
                reportError("Unable to enable characteristic $updateLabel", "address=$address characteristic=$charUuid")
                return false
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to enable BLE $updateLabel without permission")
            reportError("Unable to enable BLE $updateLabel", e.message)
            return false
        }
        
        val descriptor = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: run {
            reportError("BLE $updateLabel descriptor not found", "address=$address characteristic=$charUuid")
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeDescriptor(
                    descriptor,
                    descriptorValue
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = descriptorValue
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
            discoveredServiceSummary = "none"
            serviceDiscoveryAttempts = 0
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
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
        mainHandler.removeCallbacksAndMessages(null)
        try {
            bluetoothGatt?.close()
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to close BLE device without permission")
            reportError("Unable to close BLE device", e.message)
        }
        bluetoothGatt = null
    }

    @SuppressLint("MissingPermission")
    private fun scheduleServiceDiscovery(gatt: BluetoothGatt, delayMillis: Long) {
        mainHandler.postDelayed(
            {
                if (bluetoothGatt !== gatt || _connectionState.value == ConnectionState.DISCONNECTING ||
                    _connectionState.value == ConnectionState.DISCONNECTED
                ) {
                    return@postDelayed
                }

                serviceDiscoveryAttempts += 1
                val started = try {
                    gatt.discoverServices()
                } catch (e: SecurityException) {
                    Timber.e(e, "Unable to discover BLE services without permission")
                    reportError("Unable to discover BLE services", e.message)
                    false
                }

                if (!started) {
                    retryServiceDiscoveryOrReport(
                        gatt = gatt,
                        message = "Service discovery was not accepted by Android Bluetooth",
                        detail = "address=$address attempt=$serviceDiscoveryAttempts"
                    )
                }
            },
            delayMillis
        )
    }

    private fun retryServiceDiscoveryOrReport(
        gatt: BluetoothGatt,
        message: String,
        detail: String
    ) {
        if (serviceDiscoveryAttempts < MAX_SERVICE_DISCOVERY_ATTEMPTS) {
            Timber.w("$message; retrying service discovery for $address: $detail")
            scheduleServiceDiscovery(gatt, SERVICE_DISCOVERY_RETRY_DELAY_MS)
            return
        }

        reportError(message, "$detail attempts=$serviceDiscoveryAttempts")
        _connectionState.value = ConnectionState.DISCONNECTED
        close()
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun refreshDeviceCache(gatt: BluetoothGatt): Boolean =
        runCatching {
            gatt.javaClass.getMethod("refresh").invoke(gatt) as? Boolean ?: false
        }.getOrDefault(false)

    private fun BluetoothGattService.toSummary(): String =
        "${uuid}[${characteristics.joinToString(separator = ",") { it.toSummary() }}]"

    private fun BluetoothGattCharacteristic.toSummary(): String =
        "${uuid}:${properties.toString(16)}"

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
        private const val SERVICE_DISCOVERY_DELAY_MS = 600L
        private const val SERVICE_DISCOVERY_RETRY_DELAY_MS = 1_000L
        private const val MAX_SERVICE_DISCOVERY_ATTEMPTS = 5
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
