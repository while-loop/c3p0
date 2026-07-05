package dev.whileloop.c3p0.data.repository

import dev.whileloop.c3p0.data.dao.SessionDao
import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.entity.SessionMetricEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import javax.inject.Inject

class SessionRepositoryImpl @Inject constructor(
    private val sessionDao: SessionDao
) : SessionRepository {

    override suspend fun startSession(): Long {
        val session = SessionEntity(startTime = Instant.now())
        return sessionDao.insertSession(session)
    }

    override suspend fun endSession(id: Long, finalStats: SessionEntity) {
        val session = sessionDao.getSessionById(id) ?: return
        sessionDao.updateSession(
            finalStats.copy(
                id = id,
                startTime = session.startTime,
                endTime = finalStats.endTime ?: Instant.now()
            )
        )
    }

    override suspend fun addMetric(metric: SessionMetricEntity) {
        sessionDao.insertMetric(metric)
    }

    override fun getAllSessions(): Flow<List<SessionEntity>> {
        return sessionDao.getAllSessions()
    }

    override suspend fun getSessionsBetween(startTime: Instant, endTime: Instant): List<SessionEntity> {
        return sessionDao.getSessionsBetween(startTime, endTime)
    }

    override fun getMetricsForSession(sessionId: Long): Flow<List<SessionMetricEntity>> {
        return sessionDao.getMetricsForSession(sessionId)
    }

    override suspend fun getMetricsForSessionSnapshot(sessionId: Long): List<SessionMetricEntity> {
        return sessionDao.getMetricsForSessionSnapshot(sessionId)
    }
}
