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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    private var servicesDiscovered = false
    private var serviceDiscoveryAttempts = 0
    private var serviceDiscoveryGeneration = 0
    private var pendingCharacteristicWrite: PendingGattOperation? = null
    private var pendingDescriptorWrite: PendingGattOperation? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gattOperationMutex = Mutex()
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
                    discoveredServiceSummary = "none"
                    servicesDiscovered = false
                    serviceDiscoveryAttempts = 0
                    serviceDiscoveryGeneration += 1
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
            serviceDiscoveryGeneration += 1
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesDiscovered = true
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
                servicesDiscovered = false
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
            pendingCharacteristicWrite
                ?.takeIf { it.uuid == characteristic.uuid }
                ?.result
                ?.complete(status)
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
            pendingDescriptorWrite
                ?.takeIf { it.uuid == descriptor.uuid }
                ?.result
                ?.complete(status)
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

    fun isActive(): Boolean =
        bluetoothGatt != null &&
            _connectionState.value == ConnectionState.CONNECTED

    @SuppressLint("MissingPermission")
    suspend fun writeCharacteristic(
        serviceUuid: UUID,
        charUuid: UUID,
        data: ByteArray,
        preferWithoutResponse: Boolean = preferWriteWithoutResponse
    ): Boolean = gattOperationMutex.withLock {
        if (!hasConnectPermission()) {
            reportError("Missing Bluetooth connect permission", "write characteristic $charUuid")
            return@withLock false
        }
        if (!isActive()) {
            reportError(
                "BLE characteristic write requested while disconnected",
                "address=$address characteristic=$charUuid state=${_connectionState.value}"
            )
            return@withLock false
        }
        val service = bluetoothGatt?.getService(serviceUuid) ?: run {
            reportError("BLE service not found", "address=$address service=$serviceUuid")
            return@withLock false
        }
        val char = service.getCharacteristic(charUuid) ?: run {
            reportError("BLE characteristic not found", "address=$address characteristic=$charUuid")
            return@withLock false
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
        val operation = if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
            PendingGattOperation(charUuid)
        } else {
            null
        }
        pendingCharacteristicWrite = operation
        val started = try {
            runOnMainThread {
                char.writeType = writeType
                char.value = data
                bluetoothGatt?.writeCharacteristic(char) ?: false
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to write BLE characteristic without permission")
            reportError("Unable to write BLE characteristic", e.message)
            false
        }
        if (!started) {
            if (pendingCharacteristicWrite === operation) {
                pendingCharacteristicWrite = null
            }
            return@withLock false
        }

        if (operation == null) {
            delay(GATT_WRITE_WITHOUT_RESPONSE_SETTLE_MS)
            return@withLock isActive()
        }

        val status = withTimeoutOrNull(GATT_OPERATION_TIMEOUT_MS) {
            operation.result.await()
        }
        if (pendingCharacteristicWrite === operation) {
            pendingCharacteristicWrite = null
        }
        if (status == null) {
            reportError(
                "BLE characteristic write timed out",
                "address=$address characteristic=$charUuid"
            )
            false
        } else {
            status == BluetoothGatt.GATT_SUCCESS && isActive()
        }
    }

    suspend fun writeCharacteristicByUuidSubstring(
        charUuidSubstring: String,
        data: ByteArray,
        preferWithoutResponse: Boolean = preferWriteWithoutResponse
    ): Boolean {
        val (serviceUuid, charUuid) = findCharacteristicBySubstring(charUuidSubstring)?.uuidPair ?: run {
            reportError("BLE characteristic not found", "address=$address characteristic~=$charUuidSubstring")
            return false
        }
        return writeCharacteristic(serviceUuid, charUuid, data, preferWithoutResponse)
    }

    @SuppressLint("MissingPermission")
    suspend fun enableNotifications(serviceUuid: UUID, charUuid: UUID): Boolean {
        return enableCharacteristicUpdates(
            serviceUuid = serviceUuid,
            charUuid = charUuid,
            descriptorValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            updateLabel = "notifications"
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun enableIndications(serviceUuid: UUID, charUuid: UUID): Boolean {
        return enableCharacteristicUpdates(
            serviceUuid = serviceUuid,
            charUuid = charUuid,
            descriptorValue = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
            updateLabel = "indications"
        )
    }

    suspend fun enableUpdatesByUuidSubstring(charUuidSubstring: String): Boolean {
        val match = findCharacteristicBySubstring(charUuidSubstring) ?: run {
            reportError("BLE characteristic not found", "address=$address characteristic~=$charUuidSubstring")
            return false
        }
        val supportsIndications =
            match.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val supportsNotifications =
            match.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        return when {
            supportsIndications -> enableCharacteristicUpdates(
                serviceUuid = match.serviceUuid,
                charUuid = match.charUuid,
                descriptorValue = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
                updateLabel = "indications"
            )
            supportsNotifications -> enableCharacteristicUpdates(
                serviceUuid = match.serviceUuid,
                charUuid = match.charUuid,
                descriptorValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                updateLabel = "notifications"
            )
            else -> {
                reportError(
                    "BLE characteristic does not support updates",
                    "address=$address characteristic=${match.charUuid} properties=${match.properties.toString(16)}"
                )
                false
            }
        }
    }

    fun hasService(serviceUuid: UUID): Boolean =
        bluetoothGatt?.getService(serviceUuid) != null

    fun hasCharacteristicUuidSubstring(charUuidSubstring: String): Boolean =
        findCharacteristicBySubstring(charUuidSubstring) != null

    fun hasUpdateCharacteristicUuidSubstring(charUuidSubstring: String): Boolean =
        findCharacteristicBySubstring(charUuidSubstring)?.supportsUpdates == true

    fun hasWriteCharacteristicUuidSubstring(charUuidSubstring: String): Boolean =
        findCharacteristicBySubstring(charUuidSubstring)?.supportsWrites == true

    private fun findCharacteristicBySubstring(charUuidSubstring: String): CharacteristicMatch? {
        val needle = charUuidSubstring.lowercase(Locale.US)
        val services = bluetoothGatt?.services ?: return null
        services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                if (characteristic.uuid.toString().lowercase(Locale.US).contains(needle)) {
                    return CharacteristicMatch(
                        serviceUuid = service.uuid,
                        charUuid = characteristic.uuid,
                        properties = characteristic.properties
                    )
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableCharacteristicUpdates(
        serviceUuid: UUID,
        charUuid: UUID,
        descriptorValue: ByteArray,
        updateLabel: String
    ): Boolean = gattOperationMutex.withLock {
        if (!hasConnectPermission()) {
            reportError("Missing Bluetooth connect permission", "enable $updateLabel for $charUuid")
            return@withLock false
        }
        if (!isActive()) {
            reportError(
                "BLE $updateLabel requested while disconnected",
                "address=$address characteristic=$charUuid state=${_connectionState.value}"
            )
            return@withLock false
        }
        val service = bluetoothGatt?.getService(serviceUuid) ?: run {
            reportError("BLE service not found", "address=$address service=$serviceUuid")
            return@withLock false
        }
        val char = service.getCharacteristic(charUuid) ?: run {
            reportError("BLE characteristic not found", "address=$address characteristic=$charUuid")
            return@withLock false
        }
        try {
            val notificationSet = bluetoothGatt?.setCharacteristicNotification(char, true) ?: false
            if (!notificationSet) {
                reportError("Unable to enable characteristic $updateLabel", "address=$address characteristic=$charUuid")
                return@withLock false
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to enable BLE $updateLabel without permission")
            reportError("Unable to enable BLE $updateLabel", e.message)
            return@withLock false
        }
        
        val descriptor = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: run {
            reportError("BLE $updateLabel descriptor not found", "address=$address characteristic=$charUuid")
            return@withLock false
        }
        val operation = PendingGattOperation(descriptor.uuid)
        pendingDescriptorWrite = operation
        val started = try {
            runOnMainThread {
                descriptor.value = descriptorValue
                bluetoothGatt?.writeDescriptor(descriptor) ?: false
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Unable to write BLE descriptor without permission")
            reportError("Unable to write BLE descriptor", e.message)
            false
        }
        if (!started) {
            if (pendingDescriptorWrite === operation) {
                pendingDescriptorWrite = null
            }
            return@withLock false
        }

        val status = withTimeoutOrNull(GATT_OPERATION_TIMEOUT_MS) {
            operation.result.await()
        }
        if (pendingDescriptorWrite === operation) {
            pendingDescriptorWrite = null
        }
        if (status == null) {
            reportError(
                "BLE $updateLabel descriptor write timed out",
                "address=$address characteristic=$charUuid descriptor=${descriptor.uuid}"
            )
            false
        } else {
            status == BluetoothGatt.GATT_SUCCESS && isActive()
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
            servicesDiscovered = false
            serviceDiscoveryAttempts = 0
            serviceDiscoveryGeneration += 1
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
        completePendingGattOperations(BluetoothGatt.GATT_FAILURE)
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
        servicesDiscovered = false
        serviceDiscoveryGeneration += 1
        completePendingGattOperations(BluetoothGatt.GATT_FAILURE)
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
                servicesDiscovered = false
                val generation = ++serviceDiscoveryGeneration
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
                } else {
                    scheduleServiceDiscoveryCallbackTimeout(gatt, generation)
                }
            },
            delayMillis
        )
    }

    private fun scheduleServiceDiscoveryCallbackTimeout(gatt: BluetoothGatt, generation: Int) {
        mainHandler.postDelayed(
            {
                if (bluetoothGatt !== gatt ||
                    servicesDiscovered ||
                    generation != serviceDiscoveryGeneration ||
                    _connectionState.value == ConnectionState.DISCONNECTING ||
                    _connectionState.value == ConnectionState.DISCONNECTED
                ) {
                    return@postDelayed
                }

                retryServiceDiscoveryOrReport(
                    gatt = gatt,
                    message = "Service discovery timed out",
                    detail = "address=$address attempt=$serviceDiscoveryAttempts"
                )
            },
            SERVICE_DISCOVERY_CALLBACK_TIMEOUT_MS
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

    private fun completePendingGattOperations(status: Int) {
        pendingCharacteristicWrite?.result?.complete(status)
        pendingCharacteristicWrite = null
        pendingDescriptorWrite?.result?.complete(status)
        pendingDescriptorWrite = null
    }

    private suspend fun <T> runOnMainThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }

        val result = CompletableDeferred<Result<T>>()
        mainHandler.post {
            result.complete(runCatching(block))
        }
        return result.await().getOrThrow()
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

    private val CharacteristicMatch.uuidPair: Pair<UUID, UUID>
        get() = serviceUuid to charUuid

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
        private const val SERVICE_DISCOVERY_CALLBACK_TIMEOUT_MS = 3_000L
        private const val GATT_OPERATION_TIMEOUT_MS = 3_000L
        private const val GATT_WRITE_WITHOUT_RESPONSE_SETTLE_MS = 40L
        private const val MAX_SERVICE_DISCOVERY_ATTEMPTS = 5
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private data class CharacteristicMatch(
        val serviceUuid: UUID,
        val charUuid: UUID,
        val properties: Int
    ) {
        val supportsUpdates: Boolean
            get() = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ||
                properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0

        val supportsWrites: Boolean
            get() = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    }

    private data class PendingGattOperation(
        val uuid: UUID,
        val result: CompletableDeferred<Int> = CompletableDeferred()
    )
}
