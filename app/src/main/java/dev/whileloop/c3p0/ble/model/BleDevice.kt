package dev.whileloop.c3p0.ble.model

data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int
)
