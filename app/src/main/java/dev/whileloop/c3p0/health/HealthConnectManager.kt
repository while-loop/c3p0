package dev.whileloop.c3p0.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectManager @Inject constructor(
    private val context: Context
) {
    private val healthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    suspend fun hasAllPermissions(): Boolean {
        return try {
            healthConnectClient.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
        } catch (e: Exception) {
            Timber.e(e, "Error checking Health Connect permissions")
            false
        }
    }

    suspend fun readRawSteps(startTime: Instant, endTime: Instant): List<StepsRecord> {
        if (!hasAllPermissions()) return emptyList()

        return try {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )
            response.records
        } catch (e: Exception) {
            Timber.e(e, "Error reading steps from Health Connect")
            emptyList()
        }
    }

    suspend fun readLatestWeightKg(): Double? {
        if (!hasAllPermissions()) return null

        return try {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        Instant.now().minus(730, ChronoUnit.DAYS),
                        Instant.now()
                    ),
                    ascendingOrder = false,
                    pageSize = 1
                )
            )
            response.records.firstOrNull()?.weight?.inKilograms
        } catch (e: Exception) {
            Timber.e(e, "Error reading latest weight from Health Connect")
            null
        }
    }

    suspend fun writeSession(startTime: Instant, endTime: Instant, steps: Int, distanceMeters: Double) {
        if (!hasAllPermissions()) return

        try {
            val device = Device(
                manufacturer = "KingSmith",
                model = "WalkingPad C2",
                type = Device.TYPE_UNKNOWN
            )
            val stepsRecord = StepsRecord(
                count = steps.toLong(),
                startTime = startTime,
                endTime = endTime,
                startZoneOffset = java.time.ZoneOffset.UTC,
                endZoneOffset = java.time.ZoneOffset.UTC,
                metadata = Metadata.activelyRecorded(device)
            )
            healthConnectClient.insertRecords(listOf(stepsRecord))
        } catch (e: Exception) {
            Timber.e(e, "Error writing session to Health Connect")
        }
    }

    companion object {
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class)
        )
    }
}
