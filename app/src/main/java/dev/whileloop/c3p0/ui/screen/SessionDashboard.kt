package dev.whileloop.c3p0.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.whileloop.c3p0.ble.manager.ConnectionState
import dev.whileloop.c3p0.ble.model.TreadmillMode
import dev.whileloop.c3p0.ui.viewmodel.SessionViewModel
import java.util.Locale

@Composable
fun SessionDashboard(
    viewModel: SessionViewModel = hiltViewModel()
) {
    val status by viewModel.treadmillStatus.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isSessionActive by viewModel.isSessionActive.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Session",
                style = MaterialTheme.typography.headlineMedium
            )
            
            // Connection Status Indicators
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIndicator("Pad", connectionState == ConnectionState.CONNECTED)
                Spacer(modifier = Modifier.width(12.dp))
                StatusIndicator("Watch", false) // TODO: Connect to Watch HR manager state
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Stats Grid
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCard("Distance", String.format(Locale.US, "%.2f", status.distance / 100f), "km", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(16.dp))
            StatCard("Steps", status.steps.toString(), "steps", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCard("Time", formatTime(status.time), "min", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(16.dp))
            StatCard("Energy", "---", "kcal", Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.weight(1f))

        // Speed Control
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = { viewModel.decrementSpeed() }) {
                Text("-", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f km/h", status.speed),
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                    fontSize = 24.sp,
                    color = Color.Green,
                    fontWeight = FontWeight.Bold
                )
            }

            IconButton(onClick = { viewModel.incrementSpeed() }) {
                Icon(Icons.Default.Add, contentDescription = "Increase Speed")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Mode and Power
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSessionActive) {
                 Button(onClick = { viewModel.stopSession() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                Button(onClick = { viewModel.startSession() }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                }
            }

            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = status.mode == TreadmillMode.MANUAL,
                    onClick = { viewModel.setMode(TreadmillMode.MANUAL) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("Manual")
                }
                SegmentedButton(
                    selected = status.mode == TreadmillMode.AUTO,
                    onClick = { viewModel.setMode(TreadmillMode.AUTO) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("Automatic")
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun StatusIndicator(label: String, isConnected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isConnected) Color.Green else Color.Gray)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun StatCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(unit, style = MaterialTheme.typography.labelSmall)
        }
    }
}

fun formatTime(seconds: Int): String {
    val m = seconds / 60
    return String.format(Locale.US, "%d", m)
}
