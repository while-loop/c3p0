package dev.whileloop.c3p0.ui.screen

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun SessionDashboard(
    viewModel: SessionViewModel = hiltViewModel(),
    onNavigateToPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val status by viewModel.treadmillStatus.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val watchConnectionState by viewModel.watchConnectionState.collectAsState()
    val isSessionActive by viewModel.isSessionActive.collectAsState()
    val isSessionPaused by viewModel.isSessionPaused.collectAsState()
    val isAutoSpeedEnabled by viewModel.isAutoSpeedEnabled.collectAsState()
    val recoverableSession by viewModel.recoverableSession.collectAsState()
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
    val stepsToGoal by viewModel.stepsToGoal.collectAsState()
    val estimatedSecondsToStepGoal by viewModel.estimatedSecondsToStepGoal.collectAsState()
    val recentStepsPerMinute by viewModel.recentStepsPerMinute.collectAsState()
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
                showInactiveDeviceSheet = true
            } else {
                when (pendingSessionAction) {
                    SessionAction.Start -> viewModel.startSession()
                    SessionAction.Resume -> viewModel.resumeSession()
                    SessionAction.Recover -> viewModel.resumeRecoveredSession()
                }
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
                    SessionAction.Recover -> viewModel.resumeRecoveredSession()
                }
            },
            onDismiss = { showInactiveDeviceSheet = false }
        )
    }

    recoverableSession?.takeIf { !isSessionActive && !showPermissionSheet && !showInactiveDeviceSheet }?.let { recovered ->
        RecoveredSessionBottomSheet(
            elapsedSeconds = recovered.checkpoint.elapsedSeconds,
            distance = displayDistance(recovered.checkpoint.totalDistance, unitSystem),
            steps = recovered.checkpoint.totalSteps,
            checkpointTime = recovered.checkpoint.checkpointTime,
            onResume = {
                pendingSessionAction = SessionAction.Recover
                if (!context.hasPermissions(permissions)) {
                    showPermissionSheet = true
                } else if (shouldWarnAboutInactiveDevices(
                        connectionState,
                        watchConnectionState,
                        heartRateActive,
                        skipInactiveDeviceWarning
                    )
                ) {
                    neverAskAgain = false
                    showInactiveDeviceSheet = true
                } else {
                    viewModel.resumeRecoveredSession()
                }
            },
            onFinish = viewModel::finishRecoveredSession,
            onDiscard = viewModel::discardRecoveredSession
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = SESSION_FOOTER_RESERVED_HEIGHT),
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

            AdaptiveStatGrid(
                tiles = listOf(
                    StatTile("Distance", String.format(Locale.US, "%.2f", displayedDistance.value), displayedDistance.unit),
                    StatTile("Steps", sessionSteps.toString(), ""),
                    StatTile("Steps/min", recentStepsPerMinute?.roundToInt()?.toString() ?: "---", ""),
                    StatTile("Steps to goal", stepsToGoal?.toString() ?: "---", ""),
                    StatTile("Time to goal", formatGoalEta(estimatedSecondsToStepGoal), "est"),
                    StatTile("Elapsed", formatElapsedTime(sessionElapsedSeconds), ""),
                    StatTile("Calories", sessionCalories.toString(), "kcal"),
                    StatTile("Heart Rate", heartRateValue(currentHeartRate), "bpm"),
                    StatTile("Avg HR", heartRateValue(averageHeartRate), "bpm")
                ),
                modifier = Modifier.fillMaxWidth()
            )

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
                SpeedAdjustButton(
                    enabled = isPadReady,
                    contentDescription = "Decrease speed",
                    onStep = { viewModel.decrementSpeed() }
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

                SpeedAdjustButton(
                    enabled = isPadReady,
                    contentDescription = "Increase speed",
                    onStep = { viewModel.incrementSpeed() }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            pendingSessionAction = SessionAction.Start
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
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Start")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                val sessionModeCount = 2
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = status.mode == TreadmillMode.MANUAL && !isAutoSpeedEnabled,
                        onClick = { viewModel.setMode(TreadmillMode.MANUAL) },
                        enabled = isPadReady,
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = sessionModeCount),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Manual")
                    }
                    SegmentedButton(
                        selected = isAutoSpeedEnabled,
                        onClick = { viewModel.enableZone2Mode() },
                        enabled = isPadReady && heartRateActive,
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = sessionModeCount),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Zone 2")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecoveredSessionBottomSheet(
    elapsedSeconds: Int,
    distance: DisplayMeasurement,
    steps: Int,
    checkpointTime: Instant,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val savedAt = remember(checkpointTime) {
        checkpointTime.atZone(ZoneId.systemDefault()).format(
            DateTimeFormatter.ofPattern("MMM d 'at' h:mm a", Locale.getDefault())
        )
    }

    ModalBottomSheet(onDismissRequest = {}) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Recover session", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Saved $savedAt with ${formatElapsedTime(elapsedSeconds)}, " +
                    "$steps steps, and ${String.format(Locale.US, "%.2f", distance.value)} ${distance.unit}."
            )
            Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Resume")
            }
            FilledTonalButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Finish and upload")
            }
            TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Discard")
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard saved session?") },
            text = { Text("The saved session and its recorded metrics will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = onDiscard) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SpeedAdjustButton(
    enabled: Boolean,
    contentDescription: String,
    onStep: () -> Unit,
    content: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val latestOnStep by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    var repeatJob by remember { mutableStateOf<Job?>(null) }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    fun stopRepeating() {
        repeatJob?.cancel()
        repeatJob = null
    }

    fun startRepeating() {
        if (repeatJob != null) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        latestOnStep()
        val startedAt = SystemClock.elapsedRealtime()
        repeatJob = scope.launch {
            delay(SPEED_HOLD_REPEAT_INTERVAL_MS)
            while (isActive) {
                val heldMillis = SystemClock.elapsedRealtime() - startedAt
                val stepCount = if (heldMillis >= SPEED_HOLD_ACCELERATION_MS) {
                    SPEED_HOLD_ACCELERATED_STEP_COUNT
                } else {
                    1
                }
                repeat(stepCount) { latestOnStep() }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                delay(SPEED_HOLD_REPEAT_INTERVAL_MS)
            }
        }
    }

    DisposableEffect(enabled) {
        if (!enabled) stopRepeating()
        onDispose { stopRepeating() }
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                onClick {
                    if (!enabled) return@onClick false
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    latestOnStep()
                    true
                }
            }
            .then(
                if (enabled) {
                    Modifier.pointerInteropFilter { event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                startRepeating()
                                true
                            }
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> {
                                stopRepeating()
                                true
                            }
                            else -> true
                        }
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

@Composable
private fun LongPressStopButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
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
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
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
    Resume,
    Recover
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

private data class StatTile(
    val label: String,
    val value: String,
    val unit: String
)

@Composable
private fun AdaptiveStatGrid(
    tiles: List<StatTile>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val columns = floor(((maxWidth + STAT_GRID_GAP) / (MIN_STAT_TILE_WIDTH + STAT_GRID_GAP)).toDouble())
            .toInt()
            .coerceIn(2, 3)

        Column(verticalArrangement = Arrangement.spacedBy(STAT_GRID_GAP)) {
            tiles.chunked(columns).forEach { rowTiles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(STAT_GRID_GAP)
                ) {
                    rowTiles.forEach { tile ->
                        StatCard(
                            label = tile.label,
                            value = tile.value,
                            unit = tile.unit,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(columns - rowTiles.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
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

private fun formatGoalEta(seconds: Int?): String {
    if (seconds == null) return "---"
    if (seconds <= 0) return "Done"

    val minutes = ceil(seconds / 60.0).toInt()
    if (minutes < 60) return "${minutes}m"

    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return if (remainingMinutes == 0) {
        "${hours}h"
    } else {
        "${hours}h ${remainingMinutes}m"
    }
}

private fun heartRateValue(heartRate: Int): String =
    if (heartRate > 0) heartRate.toString() else "---"

private const val CHART_ABSOLUTE_MIN_HEART_RATE = 40
private const val CHART_ABSOLUTE_MAX_HEART_RATE = 220
private const val CHART_MIN_VISIBLE_RANGE = 20
private const val CHART_DOMAIN_PADDING_FRACTION = 0.05f
private const val CHART_MAX_HEART_RATE = 190
private val CHART_PADDING = 8.dp
private val STAT_GRID_GAP = 10.dp
private val MIN_STAT_TILE_WIDTH = 100.dp
private val SESSION_FOOTER_RESERVED_HEIGHT = 180.dp
private const val STOP_HOLD_DURATION_MS = 2_000L
private const val STOP_PROGRESS_FRAME_MS = 16L
private const val STOP_HOLD_SCALE = 2f
private const val STOP_HOLD_ANIMATION_MS = 160
private val STOP_BUTTON_SIZE = 72.dp
private val STOP_HOLD_ELEVATION = 24.dp
private val STOP_RING_STROKE_WIDTH = 4.dp
private const val HEART_RATE_FRESHNESS_WINDOW_MS = 5_000L
private const val SPEED_HOLD_REPEAT_INTERVAL_MS = 500L
private const val SPEED_HOLD_ACCELERATION_MS = 3_000L
private const val SPEED_HOLD_ACCELERATED_STEP_COUNT = 2
