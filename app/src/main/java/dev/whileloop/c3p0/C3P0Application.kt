package dev.whileloop.c3p0

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.whileloop.c3p0.ble.diagnostic.BleErrorReporter
import dev.whileloop.c3p0.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class C3P0Application : Application() {
    @Inject
    lateinit var settingsRepository: SettingsRepository
    @Inject
    lateinit var bleErrorReporter: BleErrorReporter

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        applicationScope.launch {
            settingsRepository.requestBackupIfEnabled()
        }
        applicationScope.launch {
            settingsRepository.bluetoothDebugModeEnabled.collect(bleErrorReporter::setEnabled)
        }
    }
}
