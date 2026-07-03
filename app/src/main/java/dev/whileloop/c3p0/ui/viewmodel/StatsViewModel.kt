package dev.whileloop.c3p0.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.entity.SessionMetricEntity
import dev.whileloop.c3p0.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repository: SessionRepository
) : ViewModel() {

    val allSessions = repository.getAllSessions().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    private val _selectedSessionMetrics = MutableStateFlow<List<SessionMetricEntity>>(emptyList())
    val selectedSessionMetrics: StateFlow<List<SessionMetricEntity>> = _selectedSessionMetrics

    fun selectSession(sessionId: Long) {
        viewModelScope.launch {
            repository.getMetricsForSession(sessionId).collect {
                _selectedSessionMetrics.value = it
            }
        }
    }
}
