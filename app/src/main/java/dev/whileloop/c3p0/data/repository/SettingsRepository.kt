package dev.whileloop.c3p0.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.whileloop.c3p0.data.model.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val TREADMILL_ADDRESS = stringPreferencesKey("treadmill_address")
        val WATCH_ADDRESS = stringPreferencesKey("watch_address")
        val UNIT_SYSTEM = stringPreferencesKey("unit_system")
        val SKIP_INACTIVE_DEVICE_WARNING = booleanPreferencesKey("skip_inactive_device_warning")
        val BODY_WEIGHT_KG = doublePreferencesKey("body_weight_kg")
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
    val bodyWeightKg: Flow<Double?> = context.dataStore.data.map {
        it[Keys.BODY_WEIGHT_KG]
    }

    suspend fun saveTreadmillAddress(address: String) {
        context.dataStore.edit { it[Keys.TREADMILL_ADDRESS] = address }
    }

    suspend fun saveWatchAddress(address: String) {
        context.dataStore.edit { it[Keys.WATCH_ADDRESS] = address }
    }

    suspend fun saveUnitSystem(unitSystem: UnitSystem) {
        context.dataStore.edit { it[Keys.UNIT_SYSTEM] = unitSystem.name }
    }

    suspend fun saveSkipInactiveDeviceWarning(skip: Boolean) {
        context.dataStore.edit { it[Keys.SKIP_INACTIVE_DEVICE_WARNING] = skip }
    }

    suspend fun saveBodyWeightKg(weightKg: Double) {
        context.dataStore.edit { it[Keys.BODY_WEIGHT_KG] = weightKg }
    }
}
