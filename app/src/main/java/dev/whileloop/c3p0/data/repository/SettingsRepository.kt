package dev.whileloop.c3p0.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
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
    }

    val treadmillAddress: Flow<String?> = context.dataStore.data.map { it[Keys.TREADMILL_ADDRESS] }
    val watchAddress: Flow<String?> = context.dataStore.data.map { it[Keys.WATCH_ADDRESS] }

    suspend fun saveTreadmillAddress(address: String) {
        context.dataStore.edit { it[Keys.TREADMILL_ADDRESS] = address }
    }

    suspend fun saveWatchAddress(address: String) {
        context.dataStore.edit { it[Keys.WATCH_ADDRESS] = address }
    }
}
