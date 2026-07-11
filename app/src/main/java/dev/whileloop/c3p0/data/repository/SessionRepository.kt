package dev.whileloop.c3p0.data.repository

import dev.whileloop.c3p0.data.entity.ActiveSessionCheckpointEntity
import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.entity.SessionMetricEntity
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    suspend fun startSession(startTime: java.time.Instant): Long
    suspend fun endSession(id: Long, finalStats: SessionEntity)
    suspend fun addMetric(metric: SessionMetricEntity)
    fun getAllSessions(): Flow<List<SessionEntity>>
    suspend fun getSessionsBetween(startTime: java.time.Instant, endTime: java.time.Instant): List<SessionEntity>
    suspend fun getSession(id: Long): SessionEntity?
    suspend fun getRecoverableCheckpoint(): ActiveSessionCheckpointEntity?
    suspend fun saveCheckpoint(checkpoint: ActiveSessionCheckpointEntity)
    suspend fun deleteCheckpoint(sessionId: Long)
    suspend fun discardUnfinishedSession(sessionId: Long)
    fun getMetricsForSession(sessionId: Long): Flow<List<SessionMetricEntity>>
    suspend fun getMetricsForSessionSnapshot(sessionId: Long): List<SessionMetricEntity>
}
