package dev.whileloop.c3p0.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.whileloop.c3p0.ble.manager.ConnectionState
import dev.whileloop.c3p0.ble.model.TreadmillMode
import dev.whileloop.c3p0.data.model.UnitSystem
import dev.whileloop.c3p0.ui.permission.PermissionGuidanceBottomSheet
import dev.whileloop.c3p0.ui.permission.PermissionRequestKind
import dev.whileloop.c3p0.ui.permission.hasPermissions
import dev.whileloop.c3p0.ui.permission.permissionGuidance
import dev.whileloop.c3p0.ui.permission.sessionPermissions
import dev.whileloop.c3p0.ui.viewmodel.SessionViewModel
import java.util.Locale

@Composable
fun SessionDashboard(
    viewModel: SessionViewModel = hiltViewModel(),
    onNavigateToPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val status by viewModel.treadmillStatus.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val watchConnectionState by viewModel.watchConnectionState.collectAsState()
    val isSessionActive by viewModel.isSessionActive.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()
    val skipInactiveDeviceWarning by viewModel.skipInactiveDeviceWarning.collectAsState()
    var showPermissionSheet by remember { mutableStateOf(false) }
    var showInactiveDeviceSheet by remember { mutableStateOf(false) }
    var neverAskAgain by remember { mutableStateOf(false) }
    val permissions = remember { sessionPermissions() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            if (shouldWarnAboutInactiveDevices(connectionState, watchConnectionState, skipInactiveDeviceWarning)) {
                neverAskAgain = false
                showInactiveDeviceSheet = true
            } else {
                viewModel.startSession()
            }
        }
    }
    val displayedDistance = displayDistance(status.distance, unitSystem)
    val displayedSpeed = displaySpeed(status.speed, unitSystem)

    if (showPermissionSheet) {
        PermissionGuidanceBottomSheet(
            guidance = permissionGuidance(PermissionRequestKind.Session),
            onContinue = {
                showPermissionSheet = false
                permissionLauncher.launch(permissions)
            },
            onDismiss = { showPermissionSheet = false }
        )
    }

    if (showInactiveDeviceSheet) {
        InactiveDeviceWarningBottomSheet(
            padActive = connectionState == ConnectionState.CONNECTED,
            watchActive = watchConnectionState == ConnectionState.CONNECTED,
            neverAskAgain = neverAskAgain,
            onNeverAskAgainChange = { neverAskAgain = it },
            onPair = {
                if (neverAskAgain) {
                    viewModel.updateSkipInactiveDeviceWarning(true)
                }
                showInactiveDeviceSheet = false
                onNavigateToPairing()
            },
            onContinue = {
                if (neverAskAgain) {
                    viewModel.updateSkipInactiveDeviceWarning(true)
                }
                showInactiveDeviceSheet = false
                viewModel.startSession()
            },
            onDismiss = { showInactiveDeviceSheet = false }
        )
    }

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
                StatusIndicator("Watch", watchConnectionState == ConnectionState.CONNECTED)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Stats Grid
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCard("Distance", String.format(Locale.US, "%.2f", displayedDistance.value), displayedDistance.unit, Modifier.weight(1f))
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
                    text = String.format(Locale.US, "%.1f %s", displayedSpeed.value, displayedSpeed.unit),
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
                Button(
                    onClick = {
                        if (context.hasPermissions(permissions)) {
                            if (shouldWarnAboutInactiveDevices(connectionState, watchConnectionState, skipInactiveDeviceWarning)) {
                                neverAskAgain = false
                                showInactiveDeviceSheet = true
                            } else {
                                viewModel.startSession()
                            }
                        } else {
                            showPermissionSheet = true
                        }
                    }
                ) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InactiveDeviceWarningBottomSheet(
    padActive: Boolean,
    watchActive: Boolean,
    neverAskAgain: Boolean,
    onNeverAskAgainChange: (Boolean) -> Unit,
    onPair: () -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Device not active", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                inactiveDeviceMessage(padActive, watchActive),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = neverAskAgain,
                    onCheckedChange = onNeverAskAgainChange
                )
                Text("Never ask again")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onPair,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Pair devices")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue anyway")
            }
        }
    }
}

private data class DisplayMeasurement(
    val value: Float,
    val unit: String
)

private fun displayDistance(distanceHundredthsKm: Int, unitSystem: UnitSystem): DisplayMeasurement {
    val kilometers = distanceHundredthsKm / 100f
    return if (unitSystem == UnitSystem.Imperial) {
        DisplayMeasurement(kilometers * 0.621371f, "mi")
    } else {
        DisplayMeasurement(kilometers, "km")
    }
}

private fun displaySpeed(speedKmh: Float, unitSystem: UnitSystem): DisplayMeasurement =
    if (unitSystem == UnitSystem.Imperial) {
        DisplayMeasurement(speedKmh * 0.621371f, "mph")
    } else {
        DisplayMeasurement(speedKmh, "km/h")
    }

private fun shouldWarnAboutInactiveDevices(
    padConnectionState: ConnectionState,
    watchConnectionState: ConnectionState,
    skipWarning: Boolean
): Boolean =
    !skipWarning &&
        (padConnectionState != ConnectionState.CONNECTED || watchConnectionState != ConnectionState.CONNECTED)

private fun inactiveDeviceMessage(padActive: Boolean, watchActive: Boolean): String =
    when {
        !padActive && !watchActive -> "Your WalkingPad and watch are not active. Pair them now, or continue without live device data."
        !padActive -> "Your WalkingPad is not active. Pair it now, or continue without pad controls and live distance."
        else -> "Your watch is not active. Pair it now, or continue without live heart-rate data."
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
