package dev.whileloop.c3p0.ble.model

import dev.whileloop.c3p0.data.model.UnitSystem

data class TreadmillStatus(
    val state: TreadmillState = TreadmillState.STANDBY,
    val speed: Float = 0f, // km/h
    val mode: TreadmillMode = TreadmillMode.MANUAL,
    val time: Int = 0, // seconds
    val distance: Int = 0, // units of 10m
    val steps: Int = 0,
    val hasStepCount: Boolean = false,
    val calories: Int = 0,
    val unitSystem: UnitSystem? = null
)

enum class TreadmillState {
    STOPPED, ACTIVE, STANDBY, STARTING, ERROR
}

enum class TreadmillMode {
    AUTO, MANUAL, STANDBY
}
