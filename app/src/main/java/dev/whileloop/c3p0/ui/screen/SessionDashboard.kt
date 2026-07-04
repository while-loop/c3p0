package dev.whileloop.c3p0.ui.screen

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import dev.whileloop.c3p0.ble.manager.ConnectionState
import dev.whileloop.c3p0.ble.model.TreadmillMode
import dev.whileloop.c3p0.ble.model.TreadmillState
import dev.whileloop.c3p0.data.model.UnitSystem
import dev.whileloop.c3p0.ui.permission.PermissionGuidanceBottomSheet
import dev.whileloop.c3p0.ui.permission.PermissionRequestKind
import dev.whileloop.c3p0.ui.permission.hasPermissions
import dev.whileloop.c3p0.ui.permission.permissionGuidance
import dev.whileloop.c3p0.ui.permission.sessionPermissions
import dev.whileloop.c3p0.ui.viewmodel.SessionViewModel
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun SessionDashboard(
    viewModel: SessionViewModel = hiltViewModel(),
    onNavigateToPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val status by viewModel.treadmillStatus.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val supportsNativeAutoMode by viewModel.supportsNativeAutoMode.collectAsState()
    val watchConnectionState by viewModel.watchConnectionState.collectAsState()
    val isSessionActive by viewModel.isSessionActive.collectAsState()
    val isSessionPaused by viewModel.isSessionPaused.collectAsState()
    val isAutoSpeedEnabled by viewModel.isAutoSpeedEnabled.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()
    val age by viewModel.age.collectAsState()
    val skipInactiveDeviceWarning by viewModel.skipInactiveDeviceWarning.collectAsState()
    val keepScreenOnDuringActiveSession by viewModel.keepScreenOnDuringActiveSession.collectAsState()
    val currentHeartRate by viewModel.currentHeartRate.collectAsState()
    val lastHeartRateReceivedAtMillis by viewModel.lastHeartRateReceivedAtMillis.collectAsState()
    val averageHeartRate by viewModel.averageHeartRate.collectAsState()
    val heartRateHistory by viewModel.heartRateHistory.collectAsState()
    val sessionElapsedSeconds by viewModel.sessionElapsedSeconds.collectAsState()
    val sessionDistance by viewModel.sessionDistance.collectAsState()
    val sessionSteps by viewModel.sessionSteps.collectAsState()
    val sessionCalories by viewModel.sessionCalories.collectAsState()
    val normalizedStepsToGoal by viewModel.normalizedStepsToGoal.collectAsState()
    var showPermissionSheet by remember { mutableStateOf(false) }
    var showInactiveDeviceSheet by remember { mutableStateOf(false) }
    var pendingSessionAction by remember { mutableStateOf(SessionAction.Start) }
    var neverAskAgain by remember { mutableStateOf(false) }
    var currentElapsedMillis by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    val heartRateActive = hasFreshHeartRateData(
        currentHeartRate = currentHeartRate,
        lastHeartRateReceivedAtMillis = lastHeartRateReceivedAtMillis,
        nowElapsedMillis = currentElapsedMillis
    )
    val permissions = remember { sessionPermissions() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            if (shouldWarnAboutInactiveDevices(connectionState, watchConnectionState, heartRateActive, skipInactiveDeviceWarning)) {
                neverAskAgain = false
                pendingSessionAction = SessionAction.Start
                showInactiveDeviceSheet = true
            } else {
                viewModel.startSession()
            }
        }
    }
    val displayedDistance = displayDistance(sessionDistance, unitSystem)
    val displayedSpeed = displaySpeed(status.speed, unitSystem)
    val isPadReady = connectionState == ConnectionState.CONNECTED
    val maxHeartRate = 220 - age
    val zone2MinHeartRate = (maxHeartRate * 0.60f).toInt()
    val zone2MaxHeartRate = (maxHeartRate * 0.70f).toInt()
    val keepScreenAwake = keepScreenOnDuringActiveSession && isSessionActive && !isSessionPaused

    LaunchedEffect(Unit) {
        while (true) {
            currentElapsedMillis = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    DisposableEffect(context, keepScreenAwake) {
        val window = context.findActivity()?.window
        if (keepScreenAwake) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

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
            watchConnected = watchConnectionState == ConnectionState.CONNECTED,
            heartRateActive = heartRateActive,
            currentHeartRate = currentHeartRate,
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
                when (pendingSessionAction) {
                    SessionAction.Start -> viewModel.startSession()
                    SessionAction.Resume -> viewModel.resumeSession()
                }
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
                Spacer(modifier = Modifier.width(12.dp))
                StatusIndicator("HR", heartRateActive)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Stats Grid
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCard("Distance", String.format(Locale.US, "%.2f", displayedDistance.value), displayedDistance.unit, Modifier.weight(1f))
            Spacer(modifier = Modifier.width(10.dp))
            StatCard("Steps", sessionSteps.toString(), "steps", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
        StatCard(
            "Steps to Goal",
            normalizedStepsToGoal?.toString() ?: "---",
            "normalized",
            Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCard("Elapsed", formatElapsedTime(sessionElapsedSeconds), "", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(10.dp))
            StatCard("Calories", sessionCalories.toString(), "kcal", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCard("Heart Rate", heartRateValue(currentHeartRate), "bpm", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(10.dp))
            StatCard("Avg HR", heartRateValue(averageHeartRate), "bpm", Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(12.dp))

        HeartRateHistoryChart(
            heartRates = heartRateHistory,
            zone2MinHeartRate = zone2MinHeartRate,
            zone2MaxHeartRate = zone2MaxHeartRate,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Speed Control
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(
                onClick = { viewModel.decrementSpeed() },
                enabled = isPadReady
            ) {
                Text("-", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f %s", displayedSpeed.value, displayedSpeed.unit),
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }

            IconButton(
                onClick = { viewModel.incrementSpeed() },
                enabled = isPadReady
            ) {
                Icon(Icons.Default.Add, contentDescription = "Increase Speed")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Mode and Power
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isSessionActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (isSessionPaused) {
                                if (shouldWarnAboutInactiveDevices(connectionState, watchConnectionState, heartRateActive, skipInactiveDeviceWarning)) {
                                    neverAskAgain = false
                                    pendingSessionAction = SessionAction.Resume
                                    showInactiveDeviceSheet = true
                                } else {
                                    viewModel.resumeSession()
                                }
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
                    if (isSessionPaused) {
                        Spacer(modifier = Modifier.width(12.dp))
                        LongPressStopButton(onStop = { viewModel.stopSession() })
                    }
                }
            } else {
                Button(
                    onClick = {
                        if (context.hasPermissions(permissions)) {
                            if (shouldWarnAboutInactiveDevices(connectionState, watchConnectionState, heartRateActive, skipInactiveDeviceWarning)) {
                                neverAskAgain = false
                                pendingSessionAction = SessionAction.Start
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

            Spacer(modifier = Modifier.height(12.dp))
            val sessionModeCount = 3
            val canChangeNativeAutoMode =
                isPadReady &&
                    supportsNativeAutoMode &&
                    status.state != TreadmillState.ACTIVE &&
                    (!isSessionActive || isSessionPaused)
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = status.mode == TreadmillMode.MANUAL && !isAutoSpeedEnabled,
                    onClick = { viewModel.setMode(TreadmillMode.MANUAL) },
                    enabled = isPadReady,
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = sessionModeCount)
                ) {
                    Text("Manual")
                }
                SegmentedButton(
                    selected = supportsNativeAutoMode && status.mode == TreadmillMode.AUTO && !isAutoSpeedEnabled,
                    onClick = { viewModel.setMode(TreadmillMode.AUTO) },
                    enabled = canChangeNativeAutoMode,
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = sessionModeCount)
                ) {
                    Text("Automatic")
                }
                SegmentedButton(
                    selected = isAutoSpeedEnabled,
                    onClick = { viewModel.enableZone2Mode() },
                    enabled = isPadReady && heartRateActive,
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = sessionModeCount)
                ) {
                    Text("Zone 2")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun LongPressStopButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isHolding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableStateOf(0f) }
    val ringColor = MaterialTheme.colorScheme.onError
    val trackColor = MaterialTheme.colorScheme.errorContainer
    val scale by animateFloatAsState(
        targetValue = if (isHolding) STOP_HOLD_SCALE else 1f,
        animationSpec = tween(durationMillis = STOP_HOLD_ANIMATION_MS),
        label = "stopButtonScale"
    )
    val elevation by animateDpAsState(
        targetValue = if (isHolding) STOP_HOLD_ELEVATION else 0.dp,
        animationSpec = tween(durationMillis = STOP_HOLD_ANIMATION_MS),
        label = "stopButtonElevation"
    )

    LaunchedEffect(isHolding) {
        if (isHolding) {
            val startedAt = SystemClock.elapsedRealtime()
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                holdProgress = (elapsed.toFloat() / STOP_HOLD_DURATION_MS).coerceIn(0f, 1f)
                if (elapsed >= STOP_HOLD_DURATION_MS) {
                    isHolding = false
                    onStop()
                    break
                }
                delay(STOP_PROGRESS_FRAME_MS)
            }
        } else {
            holdProgress = 0f
        }
    }

    Box(
        modifier = modifier
            .size(STOP_BUTTON_SIZE)
            .zIndex(if (isHolding) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerInput(onStop) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                isHolding = true
                waitForUpOrCancellation()
                isHolding = false
            }
        },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            shadowElevation = elevation,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Hold to stop")
                Text(
                    text = if (isHolding) "Hold" else "Stop",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = STOP_RING_STROKE_WIDTH.toPx()
            val inset = strokeWidth / 2
            val arcSize = androidx.compose.ui.geometry.Size(
                width = size.width - strokeWidth,
                height = size.height - strokeWidth
            )
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            if (holdProgress > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * holdProgress,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
private fun HeartRateHistoryChart(
    heartRates: List<Int>,
    zone2MinHeartRate: Int,
    zone2MaxHeartRate: Int,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val zone2Color = Color(0xFF16A34A)
    val zoneColors = heartRateZoneColors()
    val chartScale = remember(heartRates, zone2MinHeartRate, zone2MaxHeartRate) {
        heartRateChartScale(heartRates, zone2MinHeartRate, zone2MaxHeartRate)
    }

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
            BoxWithConstraints(
                modifier = Modifier
                    .width(40.dp)
                    .fillMaxHeight()
            ) {
                val labelHeight = 14.dp
                val maxOffset = (maxHeight - labelHeight).coerceAtLeast(0.dp)
                chartScale.yAxisLabels.forEach { label ->
                    val labelOffset = (heartRateLabelOffset(
                        heartRate = label,
                        minHeartRate = chartScale.minHeartRate,
                        range = chartScale.range,
                        height = maxHeight,
                        padding = CHART_PADDING
                    ) - labelHeight / 2).coerceIn(0.dp, maxOffset)
                    Text(
                        text = label.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (label == zone2MinHeartRate || label == zone2MaxHeartRate) {
                            zone2Color
                        } else {
                            labelColor
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = labelOffset)
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
                val padding = CHART_PADDING.toPx()
                val chartHeight = height - padding * 2
                val chartWidth = width - padding * 2
                val minHr = chartScale.minHeartRate
                val range = chartScale.range

                chartScale.yAxisLabels.forEach { label ->
                    val y = padding + chartHeight - ((label - minHr).toFloat() / range * chartHeight)
                    drawLine(
                        color = gridColor,
                        start = androidx.compose.ui.geometry.Offset(padding, y),
                        end = androidx.compose.ui.geometry.Offset(width - padding, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                listOf(zone2MinHeartRate, zone2MaxHeartRate).forEach { heartRate ->
                    val y = heartRateY(heartRate, minHr, range, padding, chartHeight)
                    drawLine(
                        color = zone2Color,
                        start = androidx.compose.ui.geometry.Offset(padding, y),
                        end = androidx.compose.ui.geometry.Offset(width - padding, y),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
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

private data class HeartRateChartScale(
    val minHeartRate: Int,
    val maxHeartRate: Int,
    val yAxisLabels: List<Int>
) {
    val range: Int = (maxHeartRate - minHeartRate).coerceAtLeast(1)
}

private fun heartRateChartScale(
    heartRates: List<Int>,
    zone2MinHeartRate: Int,
    zone2MaxHeartRate: Int
): HeartRateChartScale {
    val validHeartRates = heartRates.filter { it > 0 }
    val baseMin = (validHeartRates.minOrNull() ?: zone2MinHeartRate)
        .coerceAtMost(zone2MinHeartRate)
    val baseMax = (validHeartRates.maxOrNull() ?: zone2MaxHeartRate)
        .coerceAtLeast(zone2MaxHeartRate)
    val minPadding = (baseMin * CHART_DOMAIN_PADDING_FRACTION).roundToInt().coerceAtLeast(5)
    val maxPadding = (baseMax * CHART_DOMAIN_PADDING_FRACTION).roundToInt().coerceAtLeast(5)
    val minHeartRate = floorToNearestFive(
        (baseMin - minPadding).coerceAtLeast(CHART_ABSOLUTE_MIN_HEART_RATE)
    )
    val paddedMaxHeartRate = ceilToNearestFive(
        (baseMax + maxPadding).coerceAtMost(CHART_ABSOLUTE_MAX_HEART_RATE)
    )
    val maxHeartRate = paddedMaxHeartRate.coerceAtLeast(minHeartRate + CHART_MIN_VISIBLE_RANGE)
    val midpoint = roundToNearestFive((minHeartRate + maxHeartRate) / 2)
    val yAxisLabels = listOf(
        maxHeartRate,
        zone2MaxHeartRate,
        midpoint,
        zone2MinHeartRate,
        minHeartRate
    )
        .map { it.coerceIn(minHeartRate, maxHeartRate) }
        .distinct()
        .sortedDescending()

    return HeartRateChartScale(
        minHeartRate = minHeartRate,
        maxHeartRate = maxHeartRate,
        yAxisLabels = yAxisLabels
    )
}

private fun heartRateLabelOffset(
    heartRate: Int,
    minHeartRate: Int,
    range: Int,
    height: androidx.compose.ui.unit.Dp,
    padding: androidx.compose.ui.unit.Dp
): androidx.compose.ui.unit.Dp {
    val chartHeight = height - padding * 2
    val clampedHeartRate = heartRate.coerceIn(minHeartRate, minHeartRate + range)
    val yFraction = 1f - ((clampedHeartRate - minHeartRate).toFloat() / range)
    return padding + chartHeight * yFraction
}

private fun floorToNearestFive(value: Int): Int =
    (floor(value / 5f) * 5).toInt()

private fun ceilToNearestFive(value: Int): Int =
    (ceil(value / 5f) * 5).toInt()

private fun roundToNearestFive(value: Int): Int =
    (value / 5f).roundToInt() * 5

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
    watchConnected: Boolean,
    heartRateActive: Boolean,
    currentHeartRate: Int,
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
            DeviceReadinessRow(
                label = "WalkingPad",
                isReady = padActive,
                readyText = "Connected",
                notReadyText = "Not connected"
            )
            Spacer(modifier = Modifier.height(8.dp))
            DeviceReadinessRow(
                label = "Watch",
                isReady = watchConnected,
                readyText = "Connected",
                notReadyText = "Not connected"
            )
            Spacer(modifier = Modifier.height(8.dp))
            DeviceReadinessRow(
                label = "Heart rate",
                isReady = heartRateActive,
                readyText = "${currentHeartRate} bpm, fresh",
                notReadyText = heartRateInactiveText(watchConnected, currentHeartRate)
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

@Composable
private fun DeviceReadinessRow(
    label: String,
    isReady: Boolean,
    readyText: String,
    notReadyText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusIndicator(
            label = label,
            isConnected = isReady,
            labelStyle = MaterialTheme.typography.bodyMedium,
            dotSize = 12.dp
        )
        Text(
            text = if (isReady) readyText else notReadyText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isReady) Color.Green else Color.Gray
        )
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
    heartRateActive: Boolean,
    skipWarning: Boolean
): Boolean =
    !skipWarning &&
        (
            padConnectionState != ConnectionState.CONNECTED ||
                watchConnectionState != ConnectionState.CONNECTED ||
                !heartRateActive
            )

private fun heartRateInactiveText(watchConnected: Boolean, currentHeartRate: Int): String =
    when {
        !watchConnected -> "Watch not connected"
        currentHeartRate > 0 -> "$currentHeartRate bpm, stale"
        else -> "No recent HR"
    }

private fun hasFreshHeartRateData(
    currentHeartRate: Int,
    lastHeartRateReceivedAtMillis: Long,
    nowElapsedMillis: Long
): Boolean =
    currentHeartRate > 0 &&
        lastHeartRateReceivedAtMillis > 0L &&
        nowElapsedMillis - lastHeartRateReceivedAtMillis <= HEART_RATE_FRESHNESS_WINDOW_MS

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private enum class SessionAction {
    Start,
    Resume
}

@Composable
fun StatusIndicator(
    label: String,
    isConnected: Boolean,
    labelStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelSmall,
    dotSize: androidx.compose.ui.unit.Dp = 10.dp
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(if (isConnected) Color.Green else Color.Gray)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, style = labelStyle)
    }
}

@Composable
fun StatCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                if (unit.isNotBlank()) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
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

private const val CHART_ABSOLUTE_MIN_HEART_RATE = 40
private const val CHART_ABSOLUTE_MAX_HEART_RATE = 220
private const val CHART_MIN_VISIBLE_RANGE = 20
private const val CHART_DOMAIN_PADDING_FRACTION = 0.10f
private const val CHART_MAX_HEART_RATE = 190
private val CHART_PADDING = 8.dp
private const val STOP_HOLD_DURATION_MS = 3_000L
private const val STOP_PROGRESS_FRAME_MS = 16L
private const val STOP_HOLD_SCALE = 2f
private const val STOP_HOLD_ANIMATION_MS = 160
private val STOP_BUTTON_SIZE = 72.dp
private val STOP_HOLD_ELEVATION = 24.dp
private val STOP_RING_STROKE_WIDTH = 4.dp
private const val HEART_RATE_FRESHNESS_WINDOW_MS = 5_000L
