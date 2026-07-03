package dev.whileloop.c3p0.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.ui.viewmodel.StatsViewModel
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun StatsScreen(
    viewModel: StatsViewModel = hiltViewModel()
) {
    val sessions by viewModel.allSessions.collectAsState()
    val metrics by viewModel.selectedSessionMetrics.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("History", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (metrics.isNotEmpty()) {
            Text("Metrics selected: ${metrics.size} samples")
            Spacer(modifier = Modifier.height(16.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessions) { session ->
                SessionItem(session) {
                    viewModel.selectSession(session.id)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun SessionItem(session: SessionEntity, onClick: () -> Unit) {
    val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm", Locale.US)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Text(session.startTime.atZone(java.time.ZoneId.systemDefault()).format(formatter))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${String.format(Locale.US, "%.2f", session.totalDistance / 100f)} km")
            Text("${session.totalSteps} steps")
        }
    }
}
