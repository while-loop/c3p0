package dev.whileloop.c3p0.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
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
        return hasPermissions(PERMISSIONS)
    }

    suspend fun hasStepHistoryPermission(): Boolean {
        return hasPermissions(STEP_HISTORY_PERMISSIONS)
    }

    private suspend fun hasPermissions(requiredPermissions: Set<String>): Boolean {
        return try {
            healthConnectClient.permissionController.getGrantedPermissions().containsAll(requiredPermissions)
        } catch (e: Exception) {
            Timber.e(e, "Error checking Health Connect permissions")
            false
        }
    }

    suspend fun readRawSteps(startTime: Instant, endTime: Instant): List<StepsRecord> {
        if (!hasPermissions(STEP_HISTORY_PERMISSIONS)) return emptyList()

        return try {
            val records = mutableListOf<StepsRecord>()
            var pageToken: String? = null
            do {
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                        pageSize = STEP_READ_PAGE_SIZE,
                        pageToken = pageToken
                    )
                )
                records += response.records
                pageToken = response.pageToken
            } while (!pageToken.isNullOrBlank())

            records
        } catch (e: Exception) {
            Timber.e(e, "Error reading steps from Health Connect")
            emptyList()
        }
    }

    suspend fun readAggregatedSteps(startTime: Instant, endTime: Instant): Long {
        if (!hasPermissions(STEP_HISTORY_PERMISSIONS)) return 0L

        return try {
            val response = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )
            response[StepsRecord.COUNT_TOTAL] ?: 0L
        } catch (e: Exception) {
            Timber.e(e, "Error reading aggregated steps from Health Connect")
            0L
        }
    }

    suspend fun writeSession(startTime: Instant, endTime: Instant, steps: Int, distanceMeters: Double) {
        if (!hasPermissions(WRITE_SESSION_PERMISSIONS)) return
        if (!endTime.isAfter(startTime)) return

        try {
            val device = Device(
                manufacturer = "KingSmith",
                model = "WalkingPad C2",
                type = Device.TYPE_UNKNOWN
            )
            val startZoneOffset = ZoneId.systemDefault().rules.getOffset(startTime)
            val endZoneOffset = ZoneId.systemDefault().rules.getOffset(endTime)
            val metadata = Metadata.activelyRecorded(device)
            val exerciseSessionRecord = ExerciseSessionRecord(
                startTime = startTime,
                startZoneOffset = startZoneOffset,
                endTime = endTime,
                endZoneOffset = endZoneOffset,
                metadata = metadata,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
                title = "C3P0 WalkingPad Session"
            )
            val stepsRecord = StepsRecord(
                count = steps.coerceAtLeast(0).toLong(),
                startTime = startTime,
                endTime = endTime,
                startZoneOffset = startZoneOffset,
                endZoneOffset = endZoneOffset,
                metadata = metadata
            )
            val distanceRecord = DistanceRecord(
                startTime = startTime,
                startZoneOffset = startZoneOffset,
                endTime = endTime,
                endZoneOffset = endZoneOffset,
                distance = Length.meters(distanceMeters.coerceAtLeast(0.0)),
                metadata = metadata
            )
            healthConnectClient.insertRecords(
                listOf<Record>(
                    exerciseSessionRecord,
                    stepsRecord,
                    distanceRecord
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Error writing session to Health Connect")
        }
    }

    companion object {
        private const val STEP_READ_PAGE_SIZE = 1000
        val STEP_HISTORY_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class)
        )
        private val WRITE_SESSION_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(ExerciseSessionRecord::class)
        )
        val PERMISSIONS: Set<String> = STEP_HISTORY_PERMISSIONS + WRITE_SESSION_PERMISSIONS
    }
}
