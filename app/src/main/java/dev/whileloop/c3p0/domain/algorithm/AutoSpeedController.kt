package dev.whileloop.c3p0.domain.algorithm

import timber.log.Timber
import kotlin.math.abs

class AutoSpeedController(
    private val targetHr: Int,
    private val minSpeed: Float = 1.0f,
    private val maxSpeed: Float = 6.0f,
    private val adjustmentIntervalSeconds: Int = 30,
    private val maxAdjustment: Float = 0.5f
) {
    private val hrSamples = mutableListOf<Int>()
    private var lastAdjustmentTime = 0L

    fun addHrSample(hr: Int, timestamp: Long) {
        hrSamples.add(hr)
        
        if (lastAdjustmentTime == 0L) {
            lastAdjustmentTime = timestamp
            return
        }

        if (timestamp - lastAdjustmentTime >= adjustmentIntervalSeconds * 1000L) {
            // Time to adjust
            processAdjustment(timestamp)
        }
    }

    private fun processAdjustment(timestamp: Long) {
        if (hrSamples.isEmpty()) return
        
        val avgHr = hrSamples.average()
        hrSamples.clear()
        lastAdjustmentTime = timestamp

        val error = targetHr - avgHr
        if (abs(error) < 2.0) {
            Timber.d("HR within deadzone, no adjustment")
            return
        }

        // Simple proportional control: 0.1 km/h per 4 bpm error, capped at maxAdjustment
        var adjustment = (error / 4.0).toFloat() * 0.1f
        adjustment = adjustment.coerceIn(-maxAdjustment, maxAdjustment)
        
        Timber.d("HR Average: $avgHr, Target: $targetHr, Error: $error, Adjustment: $adjustment")
        onSpeedAdjustmentRequired?.invoke(adjustment)
    }

    var onSpeedAdjustmentRequired: ((Float) -> Unit)? = null
}
