package dev.whileloop.c3p0.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.delay
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
    val isSessionPaused by viewModel.isSessionPaused.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()
    val skipInactiveDeviceWarning by viewModel.skipInactiveDeviceWarning.collectAsState()
    val currentHeartRate by viewModel.currentHeartRate.collectAsState()
    val averageHeartRate by viewModel.averageHeartRate.collectAsState()
    val heartRateHistory by viewModel.heartRateHistory.collectAsState()
    val sessionElapsedSeconds by viewModel.sessionElapsedSeconds.collectAsState()
    val bodyWeightKg by viewModel.bodyWeightKg.collectAsState()
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
    val estimatedCalories = estimateCalories(status.speed, sessionElapsedSeconds, bodyWeightKg)

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
            .verticalScroll(rememberScrollState())
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
            Spacer(modifier = Modifier.width(10.dp))
            StatCard("Steps", status.steps.toString(), "steps", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCard("Elapsed", formatElapsedTime(sessionElapsedSeconds), "", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(10.dp))
            StatCard("Calories", estimatedCalories.toString(), "kcal", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCard("Heart Rate", heartRateValue(currentHeartRate), "bpm", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(10.dp))
            StatCard("Avg HR", heartRateValue(averageHeartRate), "bpm", Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        HeartRateHistoryChart(
            heartRates = heartRateHistory,
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (isSessionPaused) {
                                viewModel.resumeSession()
                            } else {
                                viewModel.pauseSession()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isSessionPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isSessionPaused) "Resume" else "Pause"
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isSessionPaused) "Resume" else "Pause")
                    }
                    LongPressStopButton(onStop = { viewModel.stopSession() })
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

@Composable
private fun LongPressStopButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isHolding by remember { mutableStateOf(false) }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            delay(STOP_HOLD_DURATION_MS)
            isHolding = false
            onStop()
        }
    }

    Button(
        onClick = {},
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = modifier.pointerInput(onStop) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                isHolding = true
                waitForUpOrCancellation()
                isHolding = false
            }
        }
    ) {
        Icon(Icons.Default.Stop, contentDescription = "Stop")
        Spacer(modifier = Modifier.width(6.dp))
        Text(if (isHolding) "Keep holding" else "Hold 5s")
    }
}

@Composable
private fun HeartRateHistoryChart(
    heartRates: List<Int>,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val zoneColors = heartRateZoneColors()
    val yAxisLabels = listOf(190, 160, 130, 100, 70)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Heart rate history", style = MaterialTheme.typography.titleSmall)
            Text("${heartRates.size} samples", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .width(36.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                yAxisLabels.forEach { label ->
                    Text(
                        text = label.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
            ) {
                val width = size.width
                val height = size.height
                val padding = 8.dp.toPx()
                val chartHeight = height - padding * 2
                val chartWidth = width - padding * 2
                val minHr = CHART_MIN_HEART_RATE
                val maxHr = CHART_MAX_HEART_RATE
                val range = maxHr - minHr

                yAxisLabels.forEach { label ->
                    val y = padding + chartHeight - ((label - minHr).toFloat() / range * chartHeight)
                    drawLine(
                        color = gridColor,
                        start = androidx.compose.ui.geometry.Offset(padding, y),
                        end = androidx.compose.ui.geometry.Offset(width - padding, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                if (heartRates.size < 2) return@Canvas

                val xStep = chartWidth / (heartRates.size - 1).coerceAtLeast(1)
                var previousX = padding
                var previousY = heartRateY(heartRates.first(), minHr, range, padding, chartHeight)

                heartRates.drop(1).forEachIndexed { index, heartRate ->
                    val x = padding + xStep * (index + 1)
                    val y = heartRateY(heartRate, minHr, range, padding, chartHeight)
                    drawLine(
                        color = zoneColors[heartRateZone(heartRate)],
                        start = androidx.compose.ui.geometry.Offset(previousX, previousY),
                        end = androidx.compose.ui.geometry.Offset(x, y),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    previousX = x
                    previousY = y
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        HeartRateZoneLegend(zoneColors)
    }
}

@Composable
private fun heartRateZoneColors(): List<Color> = listOf(
    MaterialTheme.colorScheme.outline,
    Color(0xFF3B82F6),
    Color(0xFF22C55E),
    Color(0xFFF59E0B),
    Color(0xFFEF4444)
)

@Composable
private fun HeartRateZoneLegend(zoneColors: List<Color>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        zoneColors.forEachIndexed { index, color ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Z$index", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun heartRateY(
    heartRate: Int,
    minHeartRate: Int,
    range: Int,
    padding: Float,
    chartHeight: Float
): Float {
    val clampedHeartRate = heartRate.coerceIn(minHeartRate, minHeartRate + range)
    return padding + chartHeight - ((clampedHeartRate - minHeartRate).toFloat() / range * chartHeight)
}

private fun heartRateZone(heartRate: Int): Int {
    val percentOfMax = heartRate.toFloat() / CHART_MAX_HEART_RATE
    return when {
        percentOfMax < 0.50f -> 0
        percentOfMax < 0.60f -> 1
        percentOfMax < 0.70f -> 2
        percentOfMax < 0.80f -> 3
        else -> 4
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
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(unit, style = MaterialTheme.typography.labelSmall)
        }
    }
}

fun formatElapsedTime(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainingSeconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, remainingSeconds)
    }
}

private fun heartRateValue(heartRate: Int): String =
    if (heartRate > 0) heartRate.toString() else "---"

private fun estimateCalories(speedKmh: Float, elapsedSeconds: Int, bodyWeightKg: Double?): Int {
    if (elapsedSeconds <= 0) return 0

    val met = when {
        speedKmh < 1f -> 1.8f
        speedKmh < 3.2f -> 2.8f
        speedKmh < 4.8f -> 3.5f
        speedKmh < 5.6f -> 4.3f
        speedKmh < 6.4f -> 5.0f
        else -> 6.3f
    }
    val hours = elapsedSeconds / 3600f
    return (met * (bodyWeightKg ?: DEFAULT_BODY_WEIGHT_KG).toFloat() * hours).toInt()
}

private const val CHART_MIN_HEART_RATE = 50
private const val CHART_MAX_HEART_RATE = 190
private const val DEFAULT_BODY_WEIGHT_KG = 70f
private const val STOP_HOLD_DURATION_MS = 5000L
