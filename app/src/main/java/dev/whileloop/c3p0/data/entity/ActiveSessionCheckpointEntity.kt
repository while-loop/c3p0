package dev.whileloop.c3p0.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "active_session_checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"], unique = true)]
)
data class ActiveSessionCheckpointEntity(
    @PrimaryKey val sessionId: Long,
    val checkpointTime: Instant,
    val elapsedSeconds: Int,
    val totalDistance: Int,
    val totalSteps: Int,
    val totalEnergy: Int,
    val heartRateTotal: Long,
    val heartRateSampleCount: Int,
    val maxHeartRate: Int,
    val wasPaused: Boolean
)
