package dev.whileloop.c3p0.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Instant,
    val endTime: Instant? = null,
    val totalDistance: Int = 0, // units of 10m
    val totalSteps: Int = 0,
    val totalEnergy: Int = 0, // kcal
    val averageHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    val healthConnectId: String? = null
)
