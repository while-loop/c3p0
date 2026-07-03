package dev.whileloop.c3p0.di

import android.content.Context
import androidx.room.Room
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): C3P0Database {
        return Room.databaseBuilder(
            context,
            C3P0Database::class.java,
            "c3p0.db"
        ).build()
    }

    @Provides
    fun provideSessionDao(db: C3P0Database) = db.sessionDao()

    @Provides
    @Singleton
    fun provideSessionRepository(sessionDao: dev.whileloop.c3p0.data.dao.SessionDao): dev.whileloop.c3p0.data.repository.SessionRepository {
        return dev.whileloop.c3p0.data.repository.SessionRepositoryImpl(sessionDao)
    }
}
