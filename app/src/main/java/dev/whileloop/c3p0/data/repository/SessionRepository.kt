package dev.whileloop.c3p0.data.repository

import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.entity.SessionMetricEntity
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    suspend fun startSession(): Long
    suspend fun endSession(id: Long, finalStats: SessionEntity)
    suspend fun addMetric(metric: SessionMetricEntity)
    fun getAllSessions(): Flow<List<SessionEntity>>
    fun getMetricsForSession(sessionId: Long): Flow<List<SessionMetricEntity>>
}
