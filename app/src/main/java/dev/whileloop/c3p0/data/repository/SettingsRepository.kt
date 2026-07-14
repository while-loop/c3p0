package dev.whileloop.c3p0.data.repository

import android.app.backup.BackupManager
import android.content.Context
import android.os.SystemClock
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.whileloop.c3p0.data.model.SessionStartMode
import dev.whileloop.c3p0.data.model.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private var lastBackupRequestElapsedMillis = 0L

    private object Keys {
        val TREADMILL_ADDRESS = stringPreferencesKey("treadmill_address")
        val WATCH_ADDRESS = stringPreferencesKey("watch_address")
        val UNIT_SYSTEM = stringPreferencesKey("unit_system")
        val SKIP_INACTIVE_DEVICE_WARNING = booleanPreferencesKey("skip_inactive_device_warning")
        val GOOGLE_DRIVE_SYNC_ENABLED = booleanPreferencesKey("google_drive_sync_enabled")
        val STEP_GOAL = intPreferencesKey("step_goal")
        val AGE = intPreferencesKey("age")
        val SESSION_START_MODE = stringPreferencesKey("session_start_mode")
        val ZONE2_MAX_SPEED_KMH = doublePreferencesKey("zone2_max_speed_kmh")
        val KEEP_SCREEN_ON_DURING_ACTIVE_SESSION = booleanPreferencesKey("keep_screen_on_during_active_session")
    }

    val treadmillAddress: Flow<String?> = context.dataStore.data.map { it[Keys.TREADMILL_ADDRESS] }
    val watchAddress: Flow<String?> = context.dataStore.data.map { it[Keys.WATCH_ADDRESS] }
    val unitSystem: Flow<UnitSystem> = context.dataStore.data.map { preferences ->
        preferences[Keys.UNIT_SYSTEM]?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() }
            ?: UnitSystem.Imperial
    }
    val skipInactiveDeviceWarning: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.SKIP_INACTIVE_DEVICE_WARNING] ?: false
    }
    val googleDriveSyncEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.GOOGLE_DRIVE_SYNC_ENABLED] ?: false
    }
    val stepGoal: Flow<Int> = context.dataStore.data.map {
        it[Keys.STEP_GOAL] ?: DEFAULT_STEP_GOAL
    }
    val age: Flow<Int> = context.dataStore.data.map {
        it[Keys.AGE] ?: DEFAULT_AGE
    }
    val sessionStartMode: Flow<SessionStartMode> = context.dataStore.data.map { preferences ->
        preferences[Keys.SESSION_START_MODE]?.let { runCatching { SessionStartMode.valueOf(it) }.getOrNull() }
            ?: SessionStartMode.Zone2
    }
    val zone2MaxSpeedKmh: Flow<Float> = context.dataStore.data.map { preferences ->
        sanitizeZone2MaxSpeedKmh(preferences[Keys.ZONE2_MAX_SPEED_KMH]?.toFloat())
    }
    val keepScreenOnDuringActiveSession: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.KEEP_SCREEN_ON_DURING_ACTIVE_SESSION] ?: false
    }

    suspend fun saveTreadmillAddress(address: String) {
        context.dataStore.edit {
            it[Keys.TREADMILL_ADDRESS] = address
            if (it[Keys.WATCH_ADDRESS] == address) {
                it.remove(Keys.WATCH_ADDRESS)
            }
        }
        requestBackupIfEnabled()
    }

    suspend fun saveWatchAddress(address: String) {
        context.dataStore.edit {
            it[Keys.WATCH_ADDRESS] = address
            if (it[Keys.TREADMILL_ADDRESS] == address) {
                it.remove(Keys.TREADMILL_ADDRESS)
            }
        }
        requestBackupIfEnabled()
    }

    suspend fun saveUnitSystem(unitSystem: UnitSystem) {
        context.dataStore.edit { it[Keys.UNIT_SYSTEM] = unitSystem.name }
        requestBackupIfEnabled()
    }

    suspend fun saveSkipInactiveDeviceWarning(skip: Boolean) {
        context.dataStore.edit { it[Keys.SKIP_INACTIVE_DEVICE_WARNING] = skip }
        requestBackupIfEnabled()
    }

    suspend fun saveGoogleDriveSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GOOGLE_DRIVE_SYNC_ENABLED] = enabled }
        if (enabled) {
            requestBackup()
        }
    }

    suspend fun saveStepGoal(goal: Int) {
        context.dataStore.edit { it[Keys.STEP_GOAL] = goal.coerceAtLeast(MIN_STEP_GOAL) }
        requestBackupIfEnabled()
    }

    suspend fun saveAge(age: Int) {
        context.dataStore.edit { it[Keys.AGE] = age.coerceIn(MIN_AGE, MAX_AGE) }
        requestBackupIfEnabled()
    }

    suspend fun saveSessionStartMode(mode: SessionStartMode) {
        context.dataStore.edit { it[Keys.SESSION_START_MODE] = mode.name }
        requestBackupIfEnabled()
    }

    suspend fun saveZone2MaxSpeedKmh(maxSpeedKmh: Float) {
        context.dataStore.edit { it[Keys.ZONE2_MAX_SPEED_KMH] = sanitizeZone2MaxSpeedKmh(maxSpeedKmh).toDouble() }
        requestBackupIfEnabled()
    }

    suspend fun saveKeepScreenOnDuringActiveSession(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON_DURING_ACTIVE_SESSION] = enabled }
        requestBackupIfEnabled()
    }

    suspend fun requestBackupIfEnabled(minIntervalMillis: Long = 0L) {
        val now = SystemClock.elapsedRealtime()
        if (
            minIntervalMillis > 0L &&
            lastBackupRequestElapsedMillis > 0L &&
            now - lastBackupRequestElapsedMillis < minIntervalMillis
        ) {
            return
        }

        if (googleDriveSyncEnabled.first()) {
            requestBackup()
        }
    }

    private fun requestBackup() {
        lastBackupRequestElapsedMillis = SystemClock.elapsedRealtime()
        BackupManager(context).dataChanged()
    }

    private companion object {
        private const val DEFAULT_STEP_GOAL = 10000
        private const val MIN_STEP_GOAL = 500
        private const val DEFAULT_AGE = 30
        private const val MIN_AGE = 1
        private const val MAX_AGE = 120
        private const val MIN_ZONE2_MAX_SPEED_KMH = 1.60934f
        private const val DEFAULT_ZONE2_MAX_SPEED_KMH = 3.5f * 1.60934f
        private const val MAX_ZONE2_MAX_SPEED_KMH = 6.0f

        private fun sanitizeZone2MaxSpeedKmh(maxSpeedKmh: Float?): Float =
            maxSpeedKmh
                ?.coerceIn(MIN_ZONE2_MAX_SPEED_KMH, MAX_ZONE2_MAX_SPEED_KMH)
                ?: DEFAULT_ZONE2_MAX_SPEED_KMH

    }
}
