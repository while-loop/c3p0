package dev.whileloop.c3p0.domain.algorithm

import timber.log.Timber
import kotlin.math.abs

class AutoSpeedController(
    private val targetHr: Int,
    private val zoneMinHr: Int = targetHr - DEFAULT_ZONE_HALF_WIDTH_BPM,
    private val zoneMaxHr: Int = targetHr + DEFAULT_ZONE_HALF_WIDTH_BPM,
    private val minSpeed: Float = 1.60934f,
    private val maxSpeed: Float = 6.0f,
    private val adjustmentIntervalSeconds: Int = 15,
    private val maxAdjustment: Float = MAX_ADJUSTMENT_KMH,
    private val memoryWindowMillis: Long = 5 * 60 * 1000L
) {
    private val samples = mutableListOf<Sample>()
    private val observations = mutableListOf<Observation>()
    private var lastAdjustmentTime = 0L
    private var lastZoneEdgeGuardTime = 0L

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

        val edgeGuardAdjustment = zone2EdgeGuardAdjustment(avgHr, avgSpeed, timestamp)
        if (edgeGuardAdjustment != null) {
            Timber.d(
                "Zone 2 edge guard adjustment. avgHr=$avgHr zone=$zoneMinHr-$zoneMaxHr " +
                    "speed=$avgSpeed adjustment=$edgeGuardAdjustment"
            )
            onSpeedAdjustmentRequired?.invoke(edgeGuardAdjustment)
            return
        }

        if (avgHr >= zoneMinHr && avgHr <= zoneMaxHr) {
            Timber.d("HR safely in Zone 2, no adjustment. avgHr=$avgHr speed=$avgSpeed")
            return
        }

        val learnedZone2Speed = learnedZone2Speed()
        val adjustment = if (avgHr < zoneMinHr) {
            belowZoneAdjustment(avgHr, avgSpeed, learnedZone2Speed)
        } else {
            aboveZoneAdjustment(avgHr, avgSpeed, learnedZone2Speed)
        }
        
        if (abs(adjustment) + MIN_ADJUSTMENT_EPSILON_KMH < MIN_ADJUSTMENT_KMH) {
            Timber.d("Zone 2 adjustment below minimum. avgHr=$avgHr speed=$avgSpeed learned=$learnedZone2Speed")
            return
        }

        Timber.d(
            "Zone 2 adjustment. avgHr=$avgHr target=$targetHr zone=$zoneMinHr-$zoneMaxHr " +
                "speed=$avgSpeed learned=$learnedZone2Speed adjustment=$adjustment"
        )
        onSpeedAdjustmentRequired?.invoke(adjustment)
    }

    private fun zone2EdgeGuardAdjustment(avgHr: Double, avgSpeed: Float, timestamp: Long): Float? {
        if (avgHr < zoneMinHr || avgHr > zoneMaxHr) return null
        if (timestamp - lastZoneEdgeGuardTime < EDGE_GUARD_INTERVAL_MILLIS) return null

        val guardBandBpm = zoneEdgeGuardBandBpm()
        val trendBpmPerMinute = heartRateTrendBpmPerMinute(timestamp)
        val adjustment = when {
            avgHr - zoneMinHr <= guardBandBpm && trendBpmPerMinute <= -MIN_EDGE_GUARD_TREND_BPM_PER_MINUTE ->
                EDGE_GUARD_ADJUSTMENT_KMH.takeIf { avgSpeed + it <= maxSpeed }
            zoneMaxHr - avgHr <= guardBandBpm && trendBpmPerMinute >= MIN_EDGE_GUARD_TREND_BPM_PER_MINUTE ->
                (-EDGE_GUARD_ADJUSTMENT_KMH).takeIf { avgSpeed + it >= minSpeed }
            else -> null
        }
        if (adjustment != null) {
            lastZoneEdgeGuardTime = timestamp
        }
        return adjustment
    }

    private fun belowZoneAdjustment(avgHr: Double, avgSpeed: Float, learnedZone2Speed: Float?): Float {
        val learnedFloor = learnedZone2Speed?.let { learnedSpeed ->
            if (avgSpeed <= learnedSpeed + LEARNED_SPEED_TOLERANCE_KMH) {
                learnedSpeed + LEARNED_STEP_KMH
            } else {
                null
            }
        }
        val errorBpm = zoneMinHr - avgHr
        val stepLimit = adjustmentStep(errorBpm)
        val proportionalTarget = avgSpeed + stepLimit
        val desiredSpeed = maxOf(learnedFloor ?: minSpeed, proportionalTarget).coerceIn(minSpeed, maxSpeed)
        return (desiredSpeed - avgSpeed).coerceIn(0f, stepLimit)
    }

    private fun aboveZoneAdjustment(avgHr: Double, avgSpeed: Float, learnedZone2Speed: Float?): Float {
        val learnedCeiling = learnedZone2Speed?.let { learnedSpeed ->
            if (avgSpeed >= learnedSpeed - LEARNED_SPEED_TOLERANCE_KMH) {
                learnedSpeed - LEARNED_STEP_KMH
            } else {
                null
            }
        }
        val errorBpm = avgHr - zoneMaxHr
        val stepLimit = adjustmentStep(errorBpm)
        val proportionalTarget = avgSpeed - stepLimit
        val desiredSpeed = minOf(learnedCeiling ?: maxSpeed, proportionalTarget).coerceIn(minSpeed, maxSpeed)
        return (desiredSpeed - avgSpeed).coerceIn(-stepLimit, 0f)
    }

    private fun adjustmentStep(errorBpm: Double): Float {
        val step = when {
            errorBpm <= SMALL_ERROR_BPM -> 0.1f * KM_PER_MILE
            errorBpm <= MEDIUM_ERROR_BPM -> 0.2f * KM_PER_MILE
            errorBpm <= LARGE_ERROR_BPM -> 0.3f * KM_PER_MILE
            errorBpm <= EXTRA_LARGE_ERROR_BPM -> 0.4f * KM_PER_MILE
            else -> 0.5f * KM_PER_MILE
        }
        return minOf(step, maxAdjustment).coerceAtLeast(MIN_ADJUSTMENT_KMH)
    }

    private fun zoneEdgeGuardBandBpm(): Double =
        maxOf(MIN_EDGE_GUARD_BPM, (zoneMaxHr - zoneMinHr) * EDGE_GUARD_ZONE_FRACTION)

    private fun heartRateTrendBpmPerMinute(timestamp: Long): Double {
        val recent = observations
            .filter { timestamp - it.timestamp <= EDGE_GUARD_TREND_WINDOW_MILLIS }
            .sortedBy { it.timestamp }
        if (recent.size < MIN_TREND_OBSERVATIONS) return 0.0

        val first = recent.first()
        val last = recent.last()
        val elapsedMinutes = (last.timestamp - first.timestamp) / 60_000.0
        if (elapsedMinutes <= 0.0) return 0.0

        return (last.avgHr - first.avgHr) / elapsedMinutes
    }

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
        private const val KM_PER_MILE = 1.60934f
        private const val MIN_ADJUSTMENT_KMH = 0.1f * KM_PER_MILE
        private const val MIN_ADJUSTMENT_EPSILON_KMH = 0.001f
        private const val MAX_ADJUSTMENT_KMH = 0.5f * KM_PER_MILE
        private const val SMALL_ERROR_BPM = 3.0
        private const val MEDIUM_ERROR_BPM = 6.0
        private const val LARGE_ERROR_BPM = 10.0
        private const val EXTRA_LARGE_ERROR_BPM = 15.0
        private const val EDGE_GUARD_ADJUSTMENT_KMH = 0.1f * KM_PER_MILE
        private const val EDGE_GUARD_INTERVAL_MILLIS = 45_000L
        private const val EDGE_GUARD_TREND_WINDOW_MILLIS = 2 * 60 * 1000L
        private const val EDGE_GUARD_ZONE_FRACTION = 0.05
        private const val MIN_EDGE_GUARD_BPM = 2.0
        private const val MIN_EDGE_GUARD_TREND_BPM_PER_MINUTE = 1.0
        private const val MIN_TREND_OBSERVATIONS = 3
        private const val LEARNED_STEP_KMH = 0.3f * KM_PER_MILE
        private const val LEARNED_SPEED_TOLERANCE_KMH = 0.1f * KM_PER_MILE
    }
}
