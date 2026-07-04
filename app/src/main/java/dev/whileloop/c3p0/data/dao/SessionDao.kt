package dev.whileloop.c3p0.data.dao

import androidx.room.*
import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.entity.SessionMetricEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Delete
    suspend fun deleteSession(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE startTime < :endTime AND endTime IS NOT NULL AND endTime > :startTime ORDER BY startTime ASC")
    suspend fun getSessionsBetween(startTime: java.time.Instant, endTime: java.time.Instant): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): SessionEntity?

    @Insert
    suspend fun insertMetric(metric: SessionMetricEntity)

    @Query("SELECT * FROM session_metrics WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMetricsForSession(sessionId: Long): Flow<List<SessionMetricEntity>>
}
