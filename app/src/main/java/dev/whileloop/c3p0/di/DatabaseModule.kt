package dev.whileloop.c3p0.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.whileloop.c3p0.data.db.C3P0Database
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS active_session_checkpoints (
                    sessionId INTEGER NOT NULL,
                    checkpointTime INTEGER NOT NULL,
                    elapsedSeconds INTEGER NOT NULL,
                    totalDistance INTEGER NOT NULL,
                    totalSteps INTEGER NOT NULL,
                    totalEnergy INTEGER NOT NULL,
                    heartRateTotal INTEGER NOT NULL,
                    heartRateSampleCount INTEGER NOT NULL,
                    maxHeartRate INTEGER NOT NULL,
                    wasPaused INTEGER NOT NULL,
                    PRIMARY KEY(sessionId),
                    FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_active_session_checkpoints_sessionId " +
                    "ON active_session_checkpoints(sessionId)"
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): C3P0Database {
        return Room.databaseBuilder(
            context,
            C3P0Database::class.java,
            "c3p0.db"
        ).addMigrations(MIGRATION_1_2).build()
    }

    @Provides
    fun provideSessionDao(db: C3P0Database) = db.sessionDao()

    @Provides
    @Singleton
    fun provideSessionRepository(sessionDao: dev.whileloop.c3p0.data.dao.SessionDao): dev.whileloop.c3p0.data.repository.SessionRepository {
        return dev.whileloop.c3p0.data.repository.SessionRepositoryImpl(sessionDao)
    }
}
