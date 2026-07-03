package dev.whileloop.c3p0.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.whileloop.c3p0.data.dao.SessionDao
import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.entity.SessionMetricEntity

@Database(
    entities = [SessionEntity::class, SessionMetricEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class C3P0Database : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
}
