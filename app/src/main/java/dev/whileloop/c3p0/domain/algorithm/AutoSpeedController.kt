package dev.whileloop.c3p0.domain.algorithm

import timber.log.Timber
import kotlin.math.abs

class AutoSpeedController(
    private val targetHr: Int,
    private val zoneMinHr: Int = targetHr - DEFAULT_ZONE_HALF_WIDTH_BPM,
    private val zoneMaxHr: Int = targetHr + DEFAULT_ZONE_HALF_WIDTH_BPM,
    private val minSpeed: Float = 1.0f,
    private val maxSpeed: Float = 6.0f,
    private val adjustmentIntervalSeconds: Int = 30,
    private val maxAdjustment: Float = 0.5f,
    private val memoryWindowMillis: Long = 5 * 60 * 1000L
) {
    private val samples = mutableListOf<Sample>()
    private val observations = mutableListOf<Observation>()
    private var lastAdjustmentTime = 0L

    fun addHrSample(hr: Int, speedKmh: Float, timestamp: Long) {
        samples.add(Sample(hr = hr, speedKmh = speedKmh.coerceIn(minSpeed, maxSpeed)))
        
        if (lastAdjustmentTime == 0L) {
            lastAdjustmentTime = timestamp
            return
        }

        if (timestamp - lastAdjustmentTime >= adjustmentIntervalSeconds * 1000L) {
            processAdjustment(timestamp)
        }
    }

    private fun processAdjustment(timestamp: Long) {
        if (samples.isEmpty()) return
        
        val avgHr = samples.map { it.hr }.average()
        val avgSpeed = samples.map { it.speedKmh }.average().toFloat()
        samples.clear()
        lastAdjustmentTime = timestamp
        observations.removeAll { timestamp - it.timestamp > memoryWindowMillis }
        observations += Observation(
            speedKmh = avgSpeed,
            avgHr = avgHr,
            zone = hrZone(avgHr),
            timestamp = timestamp
        )

        if (avgHr >= zoneMinHr && avgHr <= zoneMaxHr) {
            Timber.d("HR in Zone 2, no adjustment. avgHr=$avgHr speed=$avgSpeed")
            return
        }

        val learnedZone2Speed = learnedZone2Speed()
        val adjustment = if (avgHr < zoneMinHr) {
            belowZoneAdjustment(avgHr, avgSpeed, learnedZone2Speed)
        } else {
            aboveZoneAdjustment(avgHr, avgSpeed, learnedZone2Speed)
        }
        
        if (abs(adjustment) < MIN_ADJUSTMENT_KMH) {
            Timber.d("Zone 2 adjustment below minimum. avgHr=$avgHr speed=$avgSpeed learned=$learnedZone2Speed")
            return
        }

        Timber.d(
            "Zone 2 adjustment. avgHr=$avgHr target=$targetHr zone=$zoneMinHr-$zoneMaxHr " +
                "speed=$avgSpeed learned=$learnedZone2Speed adjustment=$adjustment"
        )
        onSpeedAdjustmentRequired?.invoke(adjustment)
    }

    private fun belowZoneAdjustment(avgHr: Double, avgSpeed: Float, learnedZone2Speed: Float?): Float {
        val learnedFloor = learnedZone2Speed?.let { learnedSpeed ->
            if (avgSpeed <= learnedSpeed + LEARNED_SPEED_TOLERANCE_KMH) {
                learnedSpeed + LEARNED_STEP_KMH
            } else {
                null
            }
        }
        val proportionalTarget = avgSpeed + proportionalStep(zoneMinHr - avgHr)
        val desiredSpeed = maxOf(learnedFloor ?: minSpeed, proportionalTarget).coerceIn(minSpeed, maxSpeed)
        return (desiredSpeed - avgSpeed).coerceIn(0f, maxAdjustment)
    }

    private fun aboveZoneAdjustment(avgHr: Double, avgSpeed: Float, learnedZone2Speed: Float?): Float {
        val learnedCeiling = learnedZone2Speed?.let { learnedSpeed ->
            if (avgSpeed >= learnedSpeed - LEARNED_SPEED_TOLERANCE_KMH) {
                learnedSpeed - LEARNED_STEP_KMH
            } else {
                null
            }
        }
        val proportionalTarget = avgSpeed - proportionalStep(avgHr - zoneMaxHr)
        val desiredSpeed = minOf(learnedCeiling ?: maxSpeed, proportionalTarget).coerceIn(minSpeed, maxSpeed)
        return (desiredSpeed - avgSpeed).coerceIn(-maxAdjustment, 0f)
    }

    private fun proportionalStep(errorBpm: Double): Float =
        ((errorBpm / 4.0).toFloat() * 0.1f).coerceIn(MIN_ADJUSTMENT_KMH, maxAdjustment)

    private fun learnedZone2Speed(): Float? {
        val zone2 = observations.filter { it.zone == HeartRateZone.Zone2 }
        if (zone2.isEmpty()) return null

        val weightedSpeedTotal = zone2.sumOf { observation ->
            val closeness = 1.0 / (1.0 + abs(targetHr - observation.avgHr))
            observation.speedKmh.toDouble() * closeness
        }
        val weightTotal = zone2.sumOf { observation ->
            1.0 / (1.0 + abs(targetHr - observation.avgHr))
        }
        return (weightedSpeedTotal / weightTotal).toFloat()
    }

    private fun hrZone(avgHr: Double): HeartRateZone =
        when {
            avgHr < zoneMinHr -> HeartRateZone.Below
            avgHr > zoneMaxHr -> HeartRateZone.Above
            else -> HeartRateZone.Zone2
        }

    var onSpeedAdjustmentRequired: ((Float) -> Unit)? = null

    private data class Sample(
        val hr: Int,
        val speedKmh: Float
    )

    private data class Observation(
        val speedKmh: Float,
        val avgHr: Double,
        val zone: HeartRateZone,
        val timestamp: Long
    )

    private enum class HeartRateZone {
        Below,
        Zone2,
        Above
    }

    private companion object {
        private const val DEFAULT_ZONE_HALF_WIDTH_BPM = 5
        private const val MIN_ADJUSTMENT_KMH = 0.05f
        private const val LEARNED_STEP_KMH = 0.2f
        private const val LEARNED_SPEED_TOLERANCE_KMH = 0.1f
    }
}
