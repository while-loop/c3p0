package dev.whileloop.c3p0.ble.model

data class TreadmillStatus(
    val state: TreadmillState = TreadmillState.STANDBY,
    val speed: Float = 0f, // km/h
    val mode: TreadmillMode = TreadmillMode.MANUAL,
    val time: Int = 0, // seconds
    val distance: Int = 0, // units of 10m
    val steps: Int = 0
)

enum class TreadmillState {
    STANDBY, MANUAL, AUTOMATIC, RUNNING, STOPPING, ERROR
}

enum class TreadmillMode {
    AUTO, MANUAL, STANDBY
}
