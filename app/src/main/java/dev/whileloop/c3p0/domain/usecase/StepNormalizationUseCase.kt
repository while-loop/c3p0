package dev.whileloop.c3p0.domain.usecase

import dev.whileloop.c3p0.health.HealthConnectManager
import java.time.Instant
import javax.inject.Inject

class StepNormalizationUseCase @Inject constructor(
    private val healthConnectManager: HealthConnectManager
) {
    suspend fun getNormalizedSteps(startTime: Instant, endTime: Instant): NormalizedStepsResult {
        val records = healthConnectManager.readRawSteps(startTime, endTime)
        
        var totalRaw = 0L
        var c3p0Steps = 0L
        var overlappingOtherSteps = 0L
        
        for (record in records) {
            totalRaw += record.count
            if (record.metadata.dataOrigin.packageName == "dev.whileloop.c3p0") {
                c3p0Steps += record.count
            } else {
                overlappingOtherSteps += record.count
            }
        }
        
        return NormalizedStepsResult(
            totalRaw = totalRaw,
            normalized = totalRaw - overlappingOtherSteps,
            c3p0Steps = c3p0Steps,
            otherSteps = overlappingOtherSteps
        )
    }
}

data class NormalizedStepsResult(
    val totalRaw: Long,
    val normalized: Long,
    val c3p0Steps: Long,
    val otherSteps: Long
)
