package dev.whileloop.c3p0.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.whileloop.c3p0.ble.manager.ConnectionState
import dev.whileloop.c3p0.ble.manager.TreadmillManager
import dev.whileloop.c3p0.ble.model.TreadmillMode
import dev.whileloop.c3p0.domain.manager.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val treadmillManager: TreadmillManager
) : ViewModel() {

    val treadmillStatus = treadmillManager.status.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        treadmillManager.status.value
    )

    val connectionState = treadmillManager.connectionState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        treadmillManager.connectionState.value
    )

    val isSessionActive = sessionManager.isSessionActive.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        sessionManager.isSessionActive.value
    )

    fun startSession() = sessionManager.startSession()
    fun stopSession() = sessionManager.stopSession()

    fun incrementSpeed() {
        viewModelScope.launch {
            val current = treadmillStatus.value.speed
            treadmillManager.setSpeed(current + 0.1f)
        }
    }

    fun decrementSpeed() {
        viewModelScope.launch {
            val current = treadmillStatus.value.speed
            treadmillManager.setSpeed((current - 0.1f).coerceAtLeast(0f))
        }
    }

    fun setMode(mode: TreadmillMode) {
        viewModelScope.launch {
            treadmillManager.setMode(mode)
        }
    }
}
