package dev.whileloop.c3p0.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.entity.SessionMetricEntity
import dev.whileloop.c3p0.data.model.UnitSystem
import dev.whileloop.c3p0.domain.usecase.NormalizedStepsResult
import dev.whileloop.c3p0.ui.viewmodel.StatsViewModel
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun StatsScreen(
    viewModel: StatsViewModel = hiltViewModel()
) {
    val sessions by viewModel.allSessions.collectAsState()
    val selectedSession by viewModel.selectedSession.collectAsState()
    val metrics by viewModel.selectedSessionMetrics.collectAsState()
    val normalizedSteps by viewModel.normalizedSteps.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("History", style = MaterialTheme.typography.headlineMedium)
            if (selectedSession != null) {
                TextButton(onClick = { viewModel.clearSelectedSession() }) {
                    Text("All")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        selectedSession?.let { session ->
            SessionDetailCard(session, metrics, normalizedSteps, unitSystem)
            Spacer(modifier = Modifier.height(16.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessions) { session ->
                SessionItem(session, unitSystem) {
                    viewModel.selectSession(session)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun SessionItem(session: SessionEntity, unitSystem: UnitSystem, onClick: () -> Unit) {
    val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm", Locale.US)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Text(session.startTime.atZone(ZoneId.systemDefault()).format(formatter))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val distance = displayHistoryDistance(session.totalDistance, unitSystem)
            Text(String.format(Locale.US, "%.2f %s", distance.value, distance.unit))
            Text("${session.totalSteps} steps")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatSessionDuration(session), style = MaterialTheme.typography.bodySmall)
            Text("${session.totalEnergy} kcal", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SessionDetailCard(
    session: SessionEntity,
    metrics: List<SessionMetricEntity>,
    normalizedSteps: NormalizedStepsResult?,
    unitSystem: UnitSystem
) {
    val distance = displayHistoryDistance(session.totalDistance, unitSystem)
    val measuredHeartRates = metrics.mapNotNull { it.heartRate }.filter { it > 0 }
    val averageSpeed = metrics.mapNotNull { it.speed }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val maxSpeed = metrics.mapNotNull { it.speed }.maxOrNull()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Selected session", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            StatLine("Duration", formatSessionDuration(session))
            StatLine("Distance", String.format(Locale.US, "%.2f %s", distance.value, distance.unit))
            StatLine("Steps", session.totalSteps.toString())
            normalizedSteps?.let {
                StatLine("Normalized steps", it.normalized.toString())
                StatLine("Other source steps", it.otherSteps.toString())
            }
            StatLine("Calories", "${session.totalEnergy} kcal")
            StatLine("Avg HR", heartRateSummary(session.averageHeartRate, measuredHeartRates.averageOrNull()))
            StatLine("Max HR", heartRateSummary(session.maxHeartRate, measuredHeartRates.maxOrNull()?.toDouble()))
            StatLine("Samples", metrics.size.toString())
            averageSpeed?.let {
                StatLine("Avg speed", formatHistorySpeed(it, unitSystem))
            }
            maxSpeed?.let {
                StatLine("Max speed", formatHistorySpeed(it, unitSystem))
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private data class HistoryDistance(val value: Float, val unit: String)

private fun displayHistoryDistance(distanceHundredthsKm: Int, unitSystem: UnitSystem): HistoryDistance {
    val kilometers = distanceHundredthsKm / 100f
    return if (unitSystem == UnitSystem.Imperial) {
        HistoryDistance(kilometers * 0.621371f, "mi")
    } else {
        HistoryDistance(kilometers, "km")
    }
}

private fun formatHistorySpeed(speedKmh: Float, unitSystem: UnitSystem): String =
    if (unitSystem == UnitSystem.Imperial) {
        String.format(Locale.US, "%.1f mph", speedKmh * 0.621371f)
    } else {
        String.format(Locale.US, "%.1f km/h", speedKmh)
    }

private fun formatSessionDuration(session: SessionEntity): String {
    val endTime = session.endTime ?: return "In progress"
    val duration = Duration.between(session.startTime, endTime).coerceAtLeast(Duration.ZERO)
    val hours = duration.toHours()
    val minutes = duration.minusHours(hours).toMinutes()
    val seconds = duration.minusHours(hours).minusMinutes(minutes).seconds
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private fun heartRateSummary(storedValue: Int, fallbackValue: Double?): String {
    val value = if (storedValue > 0) storedValue else fallbackValue?.toInt() ?: 0
    return if (value > 0) "$value bpm" else "---"
}

private fun List<Int>.averageOrNull(): Double? =
    if (isEmpty()) null else average()
