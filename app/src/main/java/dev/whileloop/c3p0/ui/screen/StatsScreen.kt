package dev.whileloop.c3p0.ui.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.entity.SessionMetricEntity
import dev.whileloop.c3p0.data.model.UnitSystem
import dev.whileloop.c3p0.domain.usecase.DailyStepHistory
import dev.whileloop.c3p0.health.WeightHistoryRecord
import dev.whileloop.c3p0.ui.permission.PermissionGuidanceBottomSheet
import dev.whileloop.c3p0.ui.permission.PermissionRequestKind
import dev.whileloop.c3p0.ui.permission.healthConnectStepHistoryPermissions
import dev.whileloop.c3p0.ui.permission.healthConnectWeightHistoryPermissions
import dev.whileloop.c3p0.ui.permission.permissionGuidance
import dev.whileloop.c3p0.ui.viewmodel.StatsViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    viewModel: StatsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val sessions by viewModel.allSessions.collectAsState()
    val selectedSession by viewModel.selectedSession.collectAsState()
    val metrics by viewModel.selectedSessionMetrics.collectAsState()
    val dailyStepHistory by viewModel.dailyStepHistory.collectAsState()
    val stepGoal by viewModel.stepGoal.collectAsState()
    val canReadHealthConnectSteps by viewModel.canReadHealthConnectSteps.collectAsState()
    val isStepHistoryLoading by viewModel.isStepHistoryLoading.collectAsState()
    val weightHistory by viewModel.weightHistory.collectAsState()
    val canReadHealthConnectWeight by viewModel.canReadHealthConnectWeight.collectAsState()
    val isWeightHistoryLoading by viewModel.isWeightHistoryLoading.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()
    var showStepPermissionSheet by remember { mutableStateOf(false) }
    var showWeightPermissionSheet by remember { mutableStateOf(false) }
    var openChartSheet by remember { mutableStateOf<StatsChartSheet?>(null) }
    var stepVisiblePeriod by remember { mutableStateOf(StepXAxisPeriod.Month) }
    var stepGrouping by remember { mutableStateOf(StepChartGrouping.Day) }
    var weightVisibleDays by remember { mutableStateOf(WeightXAxisPeriod.Month.days.toFloat()) }
    var weightGrouping by remember { mutableStateOf(WeightChartGrouping.Day) }
    var weightRightAnchorMillis by remember { mutableStateOf<Long?>(null) }
    var requestedWeightRightAnchorMillis by remember { mutableStateOf<Long?>(null) }
    var weightAnchorRequestId by remember { mutableIntStateOf(0) }
    val stepHistoryPermissions = remember { healthConnectStepHistoryPermissions() }
    val weightHistoryPermissions = remember { healthConnectWeightHistoryPermissions() }
    val chartSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val healthConnectPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        viewModel.refreshStepHistory()
    }
    val weightPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        viewModel.refreshWeightHistory()
    }

    if (showStepPermissionSheet) {
        PermissionGuidanceBottomSheet(
            guidance = permissionGuidance(PermissionRequestKind.HealthConnectSteps),
            onContinue = {
                showStepPermissionSheet = false
                if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                    healthConnectPermissionLauncher.launch(stepHistoryPermissions)
                } else {
                    Toast.makeText(
                        context,
                        "Health Connect is not available on this device.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onDismiss = { showStepPermissionSheet = false }
        )
    }

    if (showWeightPermissionSheet) {
        PermissionGuidanceBottomSheet(
            guidance = permissionGuidance(PermissionRequestKind.HealthConnectWeight),
            onContinue = {
                showWeightPermissionSheet = false
                if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                    weightPermissionLauncher.launch(weightHistoryPermissions)
                } else {
                    Toast.makeText(
                        context,
                        "Health Connect is not available on this device.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onDismiss = { showWeightPermissionSheet = false }
        )
    }

    openChartSheet?.let { sheet ->
        ModalBottomSheet(
            onDismissRequest = { openChartSheet = null },
            sheetState = chartSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = screenHeight * 0.88f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
            ) {
                when (sheet) {
                    StatsChartSheet.Steps -> HealthConnectStepHistoryCard(
                        rows = dailyStepHistory,
                        stepGoal = stepGoal,
                        canReadSteps = canReadHealthConnectSteps,
                        isLoading = isStepHistoryLoading,
                        selectedXAxisPeriod = stepVisiblePeriod,
                        selectedGrouping = stepGrouping,
                        isExpanded = true,
                        showCollapseControl = false,
                        onXAxisPeriodSelected = { stepVisiblePeriod = it },
                        onGroupingSelected = { stepGrouping = it },
                        onExpandedChange = {},
                        onEnable = { showStepPermissionSheet = true },
                        onRefresh = { viewModel.refreshStepHistory() },
                        onFullRefresh = { viewModel.refreshFullStepHistory() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                    )

                    StatsChartSheet.Weight -> HealthConnectWeightHistoryCard(
                        records = weightHistory,
                        canReadWeight = canReadHealthConnectWeight,
                        isLoading = isWeightHistoryLoading,
                        unitSystem = unitSystem,
                        visibleDays = weightVisibleDays,
                        selectedGrouping = weightGrouping,
                        onVisibleDaysChange = { weightVisibleDays = it },
                        onXAxisPeriodSelected = { period ->
                            requestedWeightRightAnchorMillis = weightRightAnchorMillis
                            weightVisibleDays = period.days.toFloat()
                            weightAnchorRequestId += 1
                        },
                        onGroupingSelected = { weightGrouping = it },
                        scrollAnchorEndTimeMillis = requestedWeightRightAnchorMillis,
                        scrollAnchorRequestId = weightAnchorRequestId,
                        onVisibleEndTimeChange = { weightRightAnchorMillis = it },
                        onEnable = { showWeightPermissionSheet = true },
                        onRefresh = { viewModel.refreshWeightHistory() },
                        onFullRefresh = { viewModel.refreshFullWeightHistory() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 360.dp)
                    )
                }
            }
        }
    }
    
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

        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StepHistoryPreviewCard(
                        rows = dailyStepHistory,
                        stepGoal = stepGoal,
                        canReadSteps = canReadHealthConnectSteps,
                        isLoading = isStepHistoryLoading,
                        onClick = { openChartSheet = StatsChartSheet.Steps },
                        modifier = Modifier
                            .weight(1f)
                            .height(164.dp)
                    )
                    WeightHistoryPreviewCard(
                        records = weightHistory,
                        canReadWeight = canReadHealthConnectWeight,
                        isLoading = isWeightHistoryLoading,
                        unitSystem = unitSystem,
                        onClick = { openChartSheet = StatsChartSheet.Weight },
                        modifier = Modifier
                            .weight(1f)
                            .height(164.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            selectedSession?.let { session ->
                item {
                    SessionDetailCard(session, metrics, unitSystem)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

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
private fun StepHistoryPreviewCard(
    rows: List<DailyStepHistory>,
    stepGoal: Int,
    canReadSteps: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val previewRows = remember(rows, stepGoal) { rows.toStepPreviewRows(stepGoal) }
    val latestSteps = previewRows.lastOrNull()?.steps

    Card(
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            ChartPreviewHeader(
                title = "Steps",
                isLoading = isLoading,
                trailingContentDescription = "Open steps chart"
            )
            Text(
                "Last 7 Days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            when {
                isLoading && previewRows.isEmpty() -> PreviewLoadingText("Loading step history...")
                !canReadSteps && previewRows.isEmpty() -> PreviewLoadingText("Enable Health Connect steps")
                previewRows.isEmpty() -> PreviewLoadingText("No step data found")
                else -> StepPreviewChart(
                    rows = previewRows,
                    stepGoal = stepGoal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            ChartPreviewValueRow(
                value = latestSteps?.let { compactSteps(it) } ?: "--",
                unit = "steps"
            )
        }
    }
}

@Composable
private fun WeightHistoryPreviewCard(
    records: List<WeightHistoryRecord>,
    canReadWeight: Boolean,
    isLoading: Boolean,
    unitSystem: UnitSystem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chartPoints = remember(records, unitSystem) {
        records.toWeightChartPoints(unitSystem, WeightChartGrouping.Day)
    }
    val previewPoints = remember(chartPoints) { chartPoints.takeLast(PREVIEW_DAY_COUNT) }
    val latestWeight = previewPoints.lastOrNull()?.trailingAverage

    Card(
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            ChartPreviewHeader(
                title = "Weight",
                isLoading = isLoading,
                trailingContentDescription = "Open weight trend chart"
            )
            Text(
                "Last 7 Days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            when {
                isLoading && previewPoints.isEmpty() -> PreviewLoadingText("Loading weight history...")
                !canReadWeight && previewPoints.isEmpty() -> PreviewLoadingText("Enable Health Connect weight")
                previewPoints.size < 2 -> PreviewLoadingText("No weight trend yet")
                else -> WeightPreviewChart(
                    points = previewPoints,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            ChartPreviewValueRow(
                value = latestWeight?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
                unit = weightUnitLabel(unitSystem)
            )
        }
    }
}

@Composable
private fun ChartPreviewHeader(
    title: String,
    isLoading: Boolean,
    trailingContentDescription: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = trailingContentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PreviewLoadingText(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChartPreviewValueRow(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 24.sp,
                lineHeight = 28.sp
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            unit,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp)
        )
    }
}

@Composable
private fun StepPreviewChart(
    rows: List<StepChartRow>,
    stepGoal: Int,
    modifier: Modifier = Modifier
) {
    val previewRows = rows.takeLast(PREVIEW_DAY_COUNT)
    val emptyLeadingSlots = PREVIEW_DAY_COUNT - previewRows.size
    val maxSteps = maxOf(
        previewRows.maxOfOrNull { it.steps } ?: 0L,
        stepGoal.toLong()
    ).coerceAtLeast(1L)
    val barColor = MaterialTheme.colorScheme.primary
    val goalBarColor = goalStepColor()
    val goalLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)

    Canvas(modifier = modifier) {
        val slotWidth = size.width / PREVIEW_DAY_COUNT
        val barWidth = (slotWidth * 0.58f).coerceAtMost(34.dp.toPx())
        previewRows.forEachIndexed { index, row ->
            val slotIndex = emptyLeadingSlots + index
            val left = (slotIndex * slotWidth) + ((slotWidth - barWidth) / 2f)
            val barHeight = size.height * barFraction(row.steps, maxSteps)
            val goalMet = row.steps >= stepGoal
            drawRoundRect(
                color = if (goalMet) goalBarColor else barColor,
                topLeft = Offset(left, size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
        }
        drawStepGoalLine(stepGoal.toLong(), maxSteps, goalLineColor)
    }
}

@Composable
private fun WeightPreviewChart(
    points: List<WeightChartPoint>,
    modifier: Modifier = Modifier
) {
    val rawColor = MaterialTheme.colorScheme.outline
    val trendColor = MaterialTheme.colorScheme.primary
    val pointFillColor = MaterialTheme.colorScheme.surface
    val minValue = points.minOf { minOf(it.rawWeight, it.trailingAverage) }
    val maxValue = points.maxOf { maxOf(it.rawWeight, it.trailingAverage) }
    val valueRange = (maxValue - minValue).coerceAtLeast(0.5)
    val startTime = points.first().time.toEpochMilli()
    val endTime = points.last().time.toEpochMilli()
    val timeRange = (endTime - startTime).coerceAtLeast(1L)

    Canvas(modifier = modifier) {
        val horizontalPadding = 4.dp.toPx()
        val verticalPadding = 8.dp.toPx()
        val chartWidth = size.width - (horizontalPadding * 2f)
        val chartHeight = size.height - (verticalPadding * 2f)

        fun xFor(timeMillis: Long): Float =
            horizontalPadding + ((timeMillis - startTime).toFloat() / timeRange) * chartWidth

        fun yFor(value: Double): Float =
            verticalPadding + ((maxValue - value).toFloat() / valueRange.toFloat()) * chartHeight

        drawSmoothWeightLine(
            points = points.map { point ->
                Offset(xFor(point.time.toEpochMilli()), yFor(point.rawWeight))
            },
            color = rawColor,
            strokeWidth = 2.dp.toPx()
        )
        drawSmoothWeightLine(
            points = points.map { point ->
                Offset(xFor(point.time.toEpochMilli()), yFor(point.trailingAverage))
            },
            color = trendColor,
            strokeWidth = 3.dp.toPx()
        )
        points.forEach { point ->
            drawCircle(
                color = pointFillColor,
                radius = 4.5.dp.toPx(),
                center = Offset(xFor(point.time.toEpochMilli()), yFor(point.trailingAverage))
            )
            drawCircle(
                color = trendColor,
                radius = 2.8.dp.toPx(),
                center = Offset(xFor(point.time.toEpochMilli()), yFor(point.trailingAverage))
            )
        }
    }
}

@Composable
private fun HealthConnectStepHistoryCard(
    rows: List<DailyStepHistory>,
    stepGoal: Int,
    canReadSteps: Boolean,
    isLoading: Boolean,
    selectedXAxisPeriod: StepXAxisPeriod,
    selectedGrouping: StepChartGrouping,
    isExpanded: Boolean,
    showCollapseControl: Boolean = true,
    onXAxisPeriodSelected: (StepXAxisPeriod) -> Unit,
    onGroupingSelected: (StepChartGrouping) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onEnable: () -> Unit,
    onRefresh: () -> Unit,
    onFullRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chartRows = remember(rows, selectedGrouping, stepGoal) {
        rows.toStepChartRows(selectedGrouping, stepGoal)
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .then(if (isExpanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showCollapseControl) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onExpandedChange(!isExpanded) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Toggle Health Connect steps"
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Health Connect steps", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Text("Health Connect steps", style = MaterialTheme.typography.titleMedium)
                }
                HistoryRefreshButton(
                    canRead = canReadSteps,
                    isLoading = isLoading,
                    onEnable = onEnable,
                    onRefresh = onRefresh,
                    onFullRefresh = onFullRefresh
                )
            }
            if (isExpanded) {
                Spacer(modifier = Modifier.height(4.dp))
                StepChartControls(
                    selectedXAxisPeriod = selectedXAxisPeriod,
                    selectedGrouping = selectedGrouping,
                    onXAxisPeriodSelected = onXAxisPeriodSelected,
                    onGroupingSelected = onGroupingSelected
                )
                Spacer(modifier = Modifier.height(8.dp))
                when {
                    isLoading && rows.isEmpty() -> Text("Loading step history...")
                    !canReadSteps && rows.isEmpty() -> Text("Enable Health Connect step access to view historical steps.")
                    rows.isEmpty() -> Text("No Health Connect step data found.")
                    chartRows.isEmpty() -> Text("No grouped step data found.")
                    else -> HealthConnectStepChart(
                        rows = chartRows,
                        visiblePeriod = selectedXAxisPeriod,
                        grouping = selectedGrouping,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthConnectWeightHistoryCard(
    records: List<WeightHistoryRecord>,
    canReadWeight: Boolean,
    isLoading: Boolean,
    unitSystem: UnitSystem,
    visibleDays: Float,
    selectedGrouping: WeightChartGrouping,
    onVisibleDaysChange: (Float) -> Unit,
    onXAxisPeriodSelected: (WeightXAxisPeriod) -> Unit,
    onGroupingSelected: (WeightChartGrouping) -> Unit,
    scrollAnchorEndTimeMillis: Long?,
    scrollAnchorRequestId: Int,
    onVisibleEndTimeChange: (Long) -> Unit,
    onEnable: () -> Unit,
    onRefresh: () -> Unit,
    onFullRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chartPoints = remember(records, unitSystem, selectedGrouping) {
        records.toWeightChartPoints(unitSystem, selectedGrouping)
    }
    var visibleSummaryPoints by remember(chartPoints) { mutableStateOf(chartPoints) }
    val summaryPoints = visibleSummaryPoints.takeIf { it.isNotEmpty() } ?: chartPoints
    val latestRange = remember(summaryPoints) { summaryPoints.dateRangeLabel() }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Weight trend", style = MaterialTheme.typography.titleMedium)
                HistoryRefreshButton(
                    canRead = canReadWeight,
                    isLoading = isLoading,
                    onEnable = onEnable,
                    onRefresh = onRefresh,
                    onFullRefresh = onFullRefresh
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            WeightChartControls(
                visibleDays = visibleDays,
                selectedGrouping = selectedGrouping,
                onXAxisPeriodSelected = onXAxisPeriodSelected,
                onGroupingSelected = onGroupingSelected
            )
            Spacer(modifier = Modifier.height(8.dp))
            when {
                isLoading && records.isEmpty() -> Text("Loading weight history...")
                !canReadWeight && records.isEmpty() -> Text("Enable Health Connect weight access to view raw weight and 7-day trend.")
                records.isEmpty() -> Text("No Health Connect weight data found.")
                chartPoints.size < 2 -> Text("Add at least two weight entries in Health Connect to show a trend.")
                else -> {
                    WeightSummaryRow(summaryPoints, unitSystem, latestRange)
                    Spacer(modifier = Modifier.height(8.dp))
                    WeightTrendChart(
                        points = chartPoints,
                        unitSystem = unitSystem,
                        visibleDays = visibleDays,
                        onVisibleDaysChange = onVisibleDaysChange,
                        onVisiblePointsChange = { visibleSummaryPoints = it },
                        scrollAnchorEndTimeMillis = scrollAnchorEndTimeMillis,
                        scrollAnchorRequestId = scrollAnchorRequestId,
                        onVisibleEndTimeChange = onVisibleEndTimeChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    WeightChartLegend()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRefreshButton(
    canRead: Boolean,
    isLoading: Boolean,
    onEnable: () -> Unit,
    onRefresh: () -> Unit,
    onFullRefresh: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                enabled = !isLoading,
                onClick = if (canRead) onRefresh else onEnable,
                onLongClickLabel = if (canRead) "Refresh full history" else null,
                onLongClick = if (canRead) onFullRefresh else null
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text = if (canRead) "Refresh" else "Enable",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun WeightSummaryRow(
    points: List<WeightChartPoint>,
    unitSystem: UnitSystem,
    dateRange: String
) {
    val average = points.map { it.rawWeight }.average()
    val difference = points.last().rawWeight - points.first().rawWeight
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Average", style = MaterialTheme.typography.labelMedium)
            Text(
                formatWeight(average, unitSystem),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(dateRange, style = MaterialTheme.typography.bodySmall)
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text("Difference", style = MaterialTheme.typography.labelMedium)
            Text(
                formatSignedWeight(difference, unitSystem),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text("first to latest", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WeightChartControls(
    visibleDays: Float,
    selectedGrouping: WeightChartGrouping,
    onXAxisPeriodSelected: (WeightXAxisPeriod) -> Unit,
    onGroupingSelected: (WeightChartGrouping) -> Unit
) {
    val matchingPeriod = remember(visibleDays) { matchingWeightXAxisPeriod(visibleDays) }
    ChartPeriodGroupingControls(
        periodOptions = WeightXAxisPeriod.entries.map { period ->
            ChartControlOption(value = period, label = period.label)
        },
        selectedPeriod = matchingPeriod,
        onPeriodSelected = onXAxisPeriodSelected,
        customSelectedPeriodLabel = matchingPeriod?.let { null } ?: formatVisibleWeightRange(visibleDays),
        groupingOptions = WeightChartGrouping.entries.map { grouping ->
            ChartControlOption(value = grouping, label = grouping.label, shortLabel = grouping.shortLabel)
        },
        selectedGrouping = selectedGrouping,
        onGroupingSelected = onGroupingSelected,
        groupingContentDescription = "Choose weight grouping"
    )
}

@Composable
private fun WeightTrendChart(
    points: List<WeightChartPoint>,
    unitSystem: UnitSystem,
    visibleDays: Float,
    onVisibleDaysChange: (Float) -> Unit,
    onVisiblePointsChange: (List<WeightChartPoint>) -> Unit,
    scrollAnchorEndTimeMillis: Long?,
    scrollAnchorRequestId: Int,
    onVisibleEndTimeChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    var selectedPointIndex by remember(points) { mutableStateOf<Int?>(null) }
    var pinchScrollAnchor by remember(points) { mutableStateOf<WeightPinchScrollAnchor?>(null) }
    var pinchScrollAnchorRequestId by remember(points) { mutableIntStateOf(0) }
    var visibleDateLabels by remember(points) { mutableStateOf(points.first().dateLabel() to points.last().dateLabel()) }
    val rawColor = MaterialTheme.colorScheme.outline
    val trendColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pointFillColor = MaterialTheme.colorScheme.surface
    val hoverLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.56f)
    val startTime = points.first().time.toEpochMilli()
    val endTime = points.last().time.toEpochMilli()
    val timeRange = (endTime - startTime).coerceAtLeast(1L)
    val timeRangeDays = (timeRange.toDouble() / MILLIS_PER_DAY).coerceAtLeast(1.0)
    val clampedVisibleDays = visibleDays.coerceIn(MIN_WEIGHT_VISIBLE_DAYS, MAX_WEIGHT_VISIBLE_DAYS)
    val widthMultiplier = (timeRangeDays / clampedVisibleDays).coerceAtLeast(1.0)
    val latestVisibleDays by rememberUpdatedState(clampedVisibleDays)
    val latestOnVisibleDaysChange by rememberUpdatedState(onVisibleDaysChange)

    LaunchedEffect(points.size, points.last().time) {
        withFrameNanos { }
        scrollState.scrollTo(scrollState.maxValue)
    }

    Column(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val contentWidth = maxWidth * widthMultiplier.toFloat()
            val viewportWidthPx = with(density) { maxWidth.toPx() }
            val contentWidthPx = with(density) { contentWidth.toPx() }
            val leftPaddingPx = with(density) { 4.dp.toPx() }
            val rightPaddingPx = with(density) { 36.dp.toPx() }
            val latestViewportWidthPx by rememberUpdatedState(viewportWidthPx)
            val latestContentWidthPx by rememberUpdatedState(contentWidthPx)
            val latestStartTime by rememberUpdatedState(startTime)
            val latestTimeRange by rememberUpdatedState(timeRange)
            val latestLeftPaddingPx by rememberUpdatedState(leftPaddingPx)
            val latestRightPaddingPx by rememberUpdatedState(rightPaddingPx)
            LaunchedEffect(
                scrollAnchorRequestId,
                scrollAnchorEndTimeMillis,
                contentWidthPx,
                viewportWidthPx,
                startTime,
                timeRange
            ) {
                val anchorEndTime = scrollAnchorEndTimeMillis ?: return@LaunchedEffect
                withFrameNanos { }
                scrollState.scrollTo(
                    scrollOffsetForVisibleEndTime(
                        endTimeMillis = anchorEndTime,
                        startTime = startTime,
                        timeRange = timeRange,
                        viewportWidthPx = viewportWidthPx,
                        contentWidthPx = contentWidthPx,
                        leftPaddingPx = leftPaddingPx,
                        rightPaddingPx = rightPaddingPx
                    )
                )
            }
            LaunchedEffect(
                pinchScrollAnchorRequestId,
                pinchScrollAnchor,
                contentWidthPx,
                viewportWidthPx,
                startTime,
                timeRange
            ) {
                val anchor = pinchScrollAnchor ?: return@LaunchedEffect
                withFrameNanos { }
                scrollState.scrollTo(
                    scrollOffsetForTimestampAtViewportX(
                        timestampMillis = anchor.timestampMillis,
                        viewportX = anchor.viewportX,
                        startTime = startTime,
                        timeRange = timeRange,
                        viewportWidthPx = viewportWidthPx,
                        contentWidthPx = contentWidthPx,
                        leftPaddingPx = leftPaddingPx,
                        rightPaddingPx = rightPaddingPx
                    )
                )
            }
            val visibleWindow = remember(
                points,
                scrollState.value,
                viewportWidthPx,
                contentWidthPx,
                startTime,
                timeRange,
                leftPaddingPx,
                rightPaddingPx
            ) {
                visibleWeightWindow(
                    points = points,
                    startTime = startTime,
                    timeRange = timeRange,
                    scrollOffsetPx = scrollState.value.toFloat(),
                    viewportWidthPx = viewportWidthPx,
                    contentWidthPx = contentWidthPx,
                    leftPaddingPx = leftPaddingPx,
                    rightPaddingPx = rightPaddingPx
                )
            }
            LaunchedEffect(visibleWindow.startTime, visibleWindow.endTime) {
                visibleDateLabels = formatWeightAxisDate(visibleWindow.startTime) to
                    formatWeightAxisDate(visibleWindow.endTime)
                onVisiblePointsChange(visibleWindow.points)
                onVisibleEndTimeChange(visibleWindow.endTime)
            }
            val latestVisibleStartTime by rememberUpdatedState(visibleWindow.startTime)
            val latestVisibleEndTime by rememberUpdatedState(visibleWindow.endTime)
            val visibleMinValue = visibleWindow.points.minOf { minOf(it.rawWeight, it.trailingAverage) }
            val visibleMaxValue = visibleWindow.points.maxOf { maxOf(it.rawWeight, it.trailingAverage) }
            val paddedMin = floor((visibleMinValue - WEIGHT_CHART_PADDING).coerceAtLeast(0.0))
            val paddedMax = ceil(visibleMaxValue + WEIGHT_CHART_PADDING).coerceAtLeast(paddedMin + 1.0)
            val valueRange = (paddedMax - paddedMin).coerceAtLeast(1.0)
            val axisLabels = weightAxisLabels(paddedMin, paddedMax)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            var previousDistance: Float? = null
                            var gestureVisibleDays = latestVisibleDays
                            var activePinchAnchor: WeightPinchScrollAnchor? = null
                            val stationaryMovementThresholdPx = 0.75.dp.toPx()
                            val sameMovementThresholdPx = 1.dp.toPx()
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    val firstPointer = pressed[0]
                                    val secondPointer = pressed[1]
                                    val currentDistance = (firstPointer.position - secondPointer.position).getDistance()
                                    val lastDistance = previousDistance
                                    if (lastDistance == null || lastDistance <= 0f) {
                                        gestureVisibleDays = latestVisibleDays
                                    } else if (currentDistance > 0f) {
                                        val rawZoom = (currentDistance / lastDistance).coerceIn(
                                            MIN_WEIGHT_PINCH_ZOOM,
                                            MAX_WEIGHT_PINCH_ZOOM
                                        )
                                        val zoom = 1f + ((rawZoom - 1f) * WEIGHT_PINCH_ZOOM_SENSITIVITY)
                                        if (abs(zoom - 1f) >= MIN_WEIGHT_PINCH_DELTA) {
                                            val firstMovement =
                                                (firstPointer.position - firstPointer.previousPosition).getDistance()
                                            val secondMovement =
                                                (secondPointer.position - secondPointer.previousPosition).getDistance()
                                            val stationaryAnchor = when {
                                                firstMovement <= stationaryMovementThresholdPx &&
                                                    secondMovement > stationaryMovementThresholdPx ->
                                                    firstPointer.position.x to secondPointer.position.x

                                                secondMovement <= stationaryMovementThresholdPx &&
                                                    firstMovement > stationaryMovementThresholdPx ->
                                                    secondPointer.position.x to firstPointer.position.x

                                                abs(firstMovement - secondMovement) <= sameMovementThresholdPx -> null

                                                firstMovement < secondMovement ->
                                                    firstPointer.position.x to secondPointer.position.x

                                                else -> secondPointer.position.x to firstPointer.position.x
                                            }
                                            val midpointX = (firstPointer.position.x + secondPointer.position.x) / 2f
                                            val anchor = activePinchAnchor ?: (
                                                stationaryAnchor?.let { (heldX, movingX) ->
                                                    if (heldX >= movingX) {
                                                        WeightPinchScrollAnchor(
                                                            timestampMillis = latestVisibleEndTime,
                                                            viewportX = latestViewportWidthPx
                                                        )
                                                    } else {
                                                        WeightPinchScrollAnchor(
                                                            timestampMillis = latestVisibleStartTime,
                                                            viewportX = 0f
                                                        )
                                                    }
                                                } ?: WeightPinchScrollAnchor(
                                                    timestampMillis = timestampAtViewportX(
                                                        viewportX = midpointX,
                                                        scrollOffsetPx = scrollState.value.toFloat(),
                                                        startTime = latestStartTime,
                                                        timeRange = latestTimeRange,
                                                        contentWidthPx = latestContentWidthPx,
                                                        leftPaddingPx = latestLeftPaddingPx,
                                                        rightPaddingPx = latestRightPaddingPx
                                                    ),
                                                    viewportX = midpointX.coerceIn(0f, latestViewportWidthPx)
                                                )
                                            ).also { activePinchAnchor = it }
                                            gestureVisibleDays = (gestureVisibleDays / zoom)
                                                .coerceIn(MIN_WEIGHT_VISIBLE_DAYS, MAX_WEIGHT_VISIBLE_DAYS)
                                            pinchScrollAnchor = anchor
                                            pinchScrollAnchorRequestId += 1
                                            latestOnVisibleDaysChange(gestureVisibleDays)
                                        }
                                    }
                                    previousDistance = currentDistance
                                    pressed.forEach { pointer -> pointer.consume() }
                                } else {
                                    previousDistance = null
                                    activePinchAnchor = null
                                }
                            }
                        }
                    }
                    .horizontalScroll(scrollState)
            ) {
                Canvas(
                    modifier = Modifier
                        .width(contentWidth)
                        .fillMaxHeight()
                        .pointerInput(points, startTime, endTime) {
                            var lastHapticIndex: Int? = null

                            fun updateSelection(x: Float) {
                                val nextIndex = selectedWeightPointIndexForX(
                                    x = x,
                                    width = size.width.toFloat(),
                                    points = points,
                                    startTime = startTime,
                                    timeRange = timeRange,
                                    leftPadding = 4.dp.toPx(),
                                    rightPadding = 36.dp.toPx()
                                )
                                selectedPointIndex = nextIndex
                                if (nextIndex != null && nextIndex != lastHapticIndex) {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    lastHapticIndex = nextIndex
                                }
                            }

                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset -> updateSelection(offset.x) },
                                onDragEnd = { selectedPointIndex = null },
                                onDragCancel = { selectedPointIndex = null },
                                onDrag = { change, _ ->
                                    updateSelection(change.position.x)
                                    change.consume()
                                }
                            )
                        }
                ) {
                    val leftPadding = leftPaddingPx
                    val rightPadding = rightPaddingPx
                    val topPadding = 8.dp.toPx()
                    val bottomPadding = 10.dp.toPx()
                    val chartWidth = size.width - leftPadding - rightPadding
                    val usableHeight = size.height - topPadding - bottomPadding

                    fun xFor(timeMillis: Long): Float =
                        leftPadding + ((timeMillis - startTime).toFloat() / timeRange) * chartWidth

                    fun yFor(value: Double): Float =
                        topPadding + ((paddedMax - value).toFloat() / valueRange.toFloat()) * usableHeight

                    axisLabels.forEach { label ->
                        val y = yFor(label)
                        drawLine(
                            color = gridColor,
                            start = Offset(leftPadding, y),
                            end = Offset(size.width - rightPadding, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    drawSmoothWeightLine(
                        points = points.map { point ->
                            Offset(xFor(point.time.toEpochMilli()), yFor(point.rawWeight))
                        },
                        color = rawColor,
                        strokeWidth = 2.dp.toPx()
                    )
                    drawSmoothWeightLine(
                        points = points.map { point ->
                            Offset(xFor(point.time.toEpochMilli()), yFor(point.trailingAverage))
                        },
                        color = trendColor,
                        strokeWidth = 2.5.dp.toPx()
                    )

                    points.forEach { point ->
                        drawCircle(
                            color = pointFillColor,
                            radius = 4.dp.toPx(),
                            center = Offset(xFor(point.time.toEpochMilli()), yFor(point.trailingAverage))
                        )
                        drawCircle(
                            color = trendColor,
                            radius = 2.5.dp.toPx(),
                            center = Offset(xFor(point.time.toEpochMilli()), yFor(point.trailingAverage))
                        )
                    }

                    selectedPointIndex?.let { index ->
                        val point = points[index]
                        val selectedX = xFor(point.time.toEpochMilli())
                        drawLine(
                            color = hoverLineColor,
                            start = Offset(selectedX, topPadding),
                            end = Offset(selectedX, size.height - bottomPadding),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawCircle(
                            color = rawColor,
                            radius = 5.dp.toPx(),
                            center = Offset(selectedX, yFor(point.rawWeight))
                        )
                        drawCircle(
                            color = pointFillColor,
                            radius = 5.dp.toPx(),
                            center = Offset(selectedX, yFor(point.trailingAverage))
                        )
                        drawCircle(
                            color = trendColor,
                            radius = 3.dp.toPx(),
                            center = Offset(selectedX, yFor(point.trailingAverage))
                        )
                    }
                }
            }
            selectedPointIndex?.let { index ->
                WeightChartHoverCard(
                    point = points[index],
                    unitSystem = unitSystem,
                    rawColor = rawColor,
                    trendColor = trendColor,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 8.dp)
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                axisLabels.forEach { label ->
                    Text(
                        text = label.toInt().toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(visibleDateLabels.first, style = MaterialTheme.typography.labelSmall)
            Text(weightUnitLabel(unitSystem), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(visibleDateLabels.second, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun WeightChartHoverCard(
    point: WeightChartPoint,
    unitSystem: UnitSystem,
    rawColor: androidx.compose.ui.graphics.Color,
    trendColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = point.displayDateLabel(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Raw ${formatWeight(point.rawWeight, unitSystem)}",
                style = MaterialTheme.typography.labelSmall,
                color = rawColor
            )
            Text(
                text = "7-day ${formatWeight(point.trailingAverage, unitSystem)}",
                style = MaterialTheme.typography.labelSmall,
                color = trendColor
            )
        }
    }
}

@Composable
private fun WeightChartLegend() {
    val rawColor = MaterialTheme.colorScheme.outline
    val trendColor = MaterialTheme.colorScheme.primary
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(color = rawColor, label = "Raw weight")
        LegendItem(color = trendColor, label = "7-day avg")
    }
}

@Composable
private fun StepChartControls(
    selectedXAxisPeriod: StepXAxisPeriod,
    selectedGrouping: StepChartGrouping,
    onXAxisPeriodSelected: (StepXAxisPeriod) -> Unit,
    onGroupingSelected: (StepChartGrouping) -> Unit
) {
    ChartPeriodGroupingControls(
        periodOptions = StepXAxisPeriod.entries.map { period ->
            ChartControlOption(value = period, label = period.label)
        },
        selectedPeriod = selectedXAxisPeriod,
        onPeriodSelected = onXAxisPeriodSelected,
        groupingOptions = StepChartGrouping.entries.map { grouping ->
            ChartControlOption(value = grouping, label = grouping.label, shortLabel = grouping.shortLabel)
        },
        selectedGrouping = selectedGrouping,
        onGroupingSelected = onGroupingSelected,
        groupingContentDescription = "Choose step grouping"
    )
}

@Composable
private fun <P, G> ChartPeriodGroupingControls(
    periodOptions: List<ChartControlOption<P>>,
    selectedPeriod: P?,
    onPeriodSelected: (P) -> Unit,
    groupingOptions: List<ChartControlOption<G>>,
    selectedGrouping: G,
    onGroupingSelected: (G) -> Unit,
    groupingContentDescription: String,
    customSelectedPeriodLabel: String? = null
) {
    var isGroupingMenuOpen by remember { mutableStateOf(false) }
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        selectedLabelColor = MaterialTheme.colorScheme.primary,
        selectedTrailingIconColor = MaterialTheme.colorScheme.primary
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        periodOptions.forEach { option ->
            FilterChip(
                selected = selectedPeriod == option.value,
                onClick = { onPeriodSelected(option.value) },
                label = { Text(option.label) },
                colors = chipColors
            )
        }
        customSelectedPeriodLabel?.let { label ->
            FilterChip(
                selected = true,
                onClick = {},
                label = { Text(label) },
                colors = chipColors
            )
        }
        Box {
            FilterChip(
                selected = true,
                onClick = { isGroupingMenuOpen = true },
                label = { Text(groupingOptions.shortLabelFor(selectedGrouping)) },
                colors = chipColors,
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = groupingContentDescription
                    )
                }
            )
            DropdownMenu(
                expanded = isGroupingMenuOpen,
                onDismissRequest = { isGroupingMenuOpen = false }
            ) {
                groupingOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            isGroupingMenuOpen = false
                            onGroupingSelected(option.value)
                        }
                    )
                }
            }
        }
    }
}

private data class ChartControlOption<T>(
    val value: T,
    val label: String,
    val shortLabel: String = label
)

private fun <T> List<ChartControlOption<T>>.shortLabelFor(value: T): String =
    firstOrNull { it.value == value }?.shortLabel ?: ""

@Composable
private fun HealthConnectStepChart(
    rows: List<StepChartRow>,
    visiblePeriod: StepXAxisPeriod,
    grouping: StepChartGrouping,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val barColor = MaterialTheme.colorScheme.primary
    val goalBarColor = goalStepColor()
    val goalLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    BoxWithConstraints(modifier = modifier) {
        val labelWidth = STEP_CHART_GOAL_LABEL_WIDTH
        val chartWidth = (maxWidth - labelWidth).coerceAtLeast(STEP_CHART_MIN_BAR_SLOT_WIDTH)
        val visibleBarSlots = visiblePeriod.visibleBarSlots(grouping)
        val barSlotWidth = stepChartBarSlotWidth(chartWidth, visibleBarSlots)
        val barWidth = stepChartBarWidth(barSlotWidth)
        val interBarSpacingWidth = STEP_CHART_BAR_SPACING * rows.size.coerceAtLeast(0).toFloat()
        val contentWidth = maxOf(
            chartWidth,
            (barSlotWidth * rows.size.toFloat()) + interBarSpacingWidth + STEP_CHART_EDGE_PADDING
        )
        val visibleRows = visibleStepRows(
            rows = rows,
            scrollOffsetPx = scrollState.value.toFloat(),
            viewportWidthPx = with(density) { chartWidth.toPx() },
            barSlotWidthPx = with(density) { barSlotWidth.toPx() },
            barSpacingPx = with(density) { STEP_CHART_BAR_SPACING.toPx() },
            periodDays = visiblePeriod.days
        )
        val maxSteps = maxOf(
            visibleRows.maxOfOrNull { it.steps } ?: 0L,
            visibleRows.maxOfOrNull { it.goalSteps } ?: 0L
        ).coerceAtLeast(1L)
        val goalGuideSteps = visibleRows.maxOfOrNull { it.goalSteps } ?: 0L
        val goalFraction = (goalGuideSteps.toFloat() / maxSteps.toFloat()).coerceIn(0f, 1f)

        LaunchedEffect(rows.size, visibleBarSlots, contentWidth, chartWidth) {
            withFrameNanos { }
            scrollState.scrollTo(scrollState.maxValue)
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .width(labelWidth)
                    .fillMaxHeight()
                    .padding(end = 6.dp, bottom = STEP_CHART_X_AXIS_LABEL_HEIGHT)
            ) {
                val labelHeight = 16.dp
                val yOffset = ((maxHeight - labelHeight) * (1f - goalFraction))
                    .coerceIn(0.dp, (maxHeight - labelHeight).coerceAtLeast(0.dp))
                Text(
                    text = goalGuideLabel(goalGuideSteps),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = yOffset)
                )
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .width(contentWidth)
                            .fillMaxHeight()
                            .horizontalScroll(scrollState),
                        horizontalArrangement = Arrangement.spacedBy(STEP_CHART_BAR_SPACING, Alignment.End),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        rows.forEach { row ->
                            val stepFraction = barFraction(row.steps, maxSteps)
                            val goalMet = row.steps >= row.goalSteps
                            Box(
                                modifier = Modifier
                                    .width(barSlotWidth)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(barWidth)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(trackColor),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight(stepFraction)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (goalMet) goalBarColor else barColor)
                                            .align(Alignment.BottomCenter)
                                    )
                                    StepBarValueLabel(
                                        text = compactSteps(row.steps),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.align(Alignment.BottomCenter)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(STEP_CHART_EDGE_PADDING))
                    }
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawStepGoalLine(goalGuideSteps, maxSteps, goalLineColor)
                    }
                }
                Row(
                    modifier = Modifier
                        .width(contentWidth)
                        .height(STEP_CHART_X_AXIS_LABEL_HEIGHT)
                        .horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(STEP_CHART_BAR_SPACING, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rows.forEach { row ->
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(barSlotWidth)
                        )
                    }
                    Spacer(modifier = Modifier.width(STEP_CHART_EDGE_PADDING))
                }
            }
        }
    }
}

private fun stepChartBarSlotWidth(width: androidx.compose.ui.unit.Dp, rowCount: Int): androidx.compose.ui.unit.Dp {
    if (rowCount <= 0) return STEP_CHART_BAR_SLOT_WIDTH
    val fittedWidth = width / rowCount.toFloat()
    return fittedWidth.coerceIn(STEP_CHART_MIN_BAR_SLOT_WIDTH, STEP_CHART_BAR_SLOT_WIDTH)
}

private fun stepChartBarWidth(slotWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    (slotWidth - 4.dp).coerceIn(STEP_CHART_MIN_BAR_WIDTH, STEP_CHART_BAR_WIDTH)

private fun goalGuideLabel(goalSteps: Long): String =
    "Goal ${compactSteps(goalSteps)}"

private fun DrawScope.drawStepGoalLine(
    stepGoal: Long,
    maxSteps: Long,
    color: Color
) {
    if (stepGoal <= 0L || maxSteps <= 0L) return
    val goalFraction = (stepGoal.toFloat() / maxSteps.toFloat()).coerceIn(0f, 1f)
    val y = size.height * (1f - goalFraction)
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
    )
}

@Composable
private fun StepBarValueLabel(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    backgroundColor: androidx.compose.ui.graphics.Color? = null
) {
    val labelModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 1.dp, vertical = 2.dp)
        .then(
            if (backgroundColor != null) {
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(backgroundColor)
                    .padding(horizontal = 1.dp)
            } else {
                Modifier
            }
        )

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = labelModifier
    )
}

@Composable
private fun goalStepColor(): Color =
    if (isSystemInDarkTheme()) {
        Color(0xFF81C784)
    } else {
        Color(0xFF2E7D32)
    }

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private enum class StepXAxisPeriod(val label: String, val days: Int) {
    Week("1W", 7),
    Month("1M", 30),
    ThreeMonths("3M", 90),
    SixMonths("6M", 180),
    Year("1Y", 365)
}

private enum class StepChartGrouping(val label: String, val shortLabel: String) {
    Day("Day", "D"),
    Week("Week", "W"),
    Month("Month", "M")
}

private fun StepXAxisPeriod.visibleBarSlots(grouping: StepChartGrouping): Int =
    when (grouping) {
        StepChartGrouping.Day -> days
        StepChartGrouping.Week -> ceil(days / DAYS_PER_WEEK.toDouble()).toInt()
        StepChartGrouping.Month -> ceil(days / AVERAGE_DAYS_PER_MONTH).toInt()
    }.coerceAtLeast(1)

private fun visibleStepRows(
    rows: List<StepChartRow>,
    scrollOffsetPx: Float,
    viewportWidthPx: Float,
    barSlotWidthPx: Float,
    barSpacingPx: Float,
    periodDays: Int
): List<StepChartRow> {
    if (rows.isEmpty()) return emptyList()

    val rowStridePx = (barSlotWidthPx + barSpacingPx).coerceAtLeast(1f)
    val firstVisibleIndex = floor(scrollOffsetPx / rowStridePx)
        .toInt()
        .coerceIn(0, rows.lastIndex)
    val lastVisibleIndex = ceil((scrollOffsetPx + viewportWidthPx) / rowStridePx)
        .toInt()
        .coerceIn(firstVisibleIndex, rows.lastIndex)
    val visibleStartDate = rows[firstVisibleIndex].startDate
    val visibleEndDate = rows[lastVisibleIndex].startDate
    val scaleStartDate = visibleStartDate.minusDays(periodDays.toLong())
    val scaleEndDate = visibleEndDate.plusDays(periodDays.toLong())

    return rows
        .filter { row -> row.startDate in scaleStartDate..scaleEndDate }
        .ifEmpty { rows.subList(firstVisibleIndex, lastVisibleIndex + 1) }
}

private enum class StatsChartSheet {
    Steps,
    Weight
}

private data class StepChartRow(
    val startDate: LocalDate,
    val label: String,
    val steps: Long,
    val goalSteps: Long
)

private fun List<DailyStepHistory>.toStepChartRows(
    grouping: StepChartGrouping,
    stepGoal: Int
): List<StepChartRow> {
    val chronological = sortedBy { it.date }
    val dailyGoal = stepGoal.toLong().coerceAtLeast(0L)
    return when (grouping) {
        StepChartGrouping.Day -> chronological.map { row ->
            StepChartRow(
                startDate = row.date,
                label = row.date.format(DateTimeFormatter.ofPattern("M/d", Locale.US)),
                steps = row.steps,
                goalSteps = dailyGoal
            )
        }
        StepChartGrouping.Week -> chronological
            .groupBy { row ->
                row.date.with(ChronoField.DAY_OF_WEEK, 1)
            }
            .toSortedMap()
            .map { (weekStart, weekRows) ->
                StepChartRow(
                    startDate = weekStart,
                    label = weekStart.format(DateTimeFormatter.ofPattern("M/d", Locale.US)),
                    steps = weekRows.sumOf { it.steps },
                    goalSteps = dailyGoal * DAYS_PER_WEEK
                )
            }
        StepChartGrouping.Month -> chronological
            .groupBy { row -> row.date.withDayOfMonth(1) }
            .toSortedMap()
            .map { (monthStart, monthRows) ->
                StepChartRow(
                    startDate = monthStart,
                    label = monthStart.format(DateTimeFormatter.ofPattern("MMM", Locale.US)),
                    steps = monthRows.sumOf { it.steps },
                    goalSteps = dailyGoal * monthStart.lengthOfMonth().toLong()
                )
            }
    }.dropLeadingEmptyRows()
}

private fun List<DailyStepHistory>.toStepPreviewRows(stepGoal: Int): List<StepChartRow> {
    val dailyGoal = stepGoal.toLong().coerceAtLeast(0L)
    return sortedBy { it.date }
        .takeLast(PREVIEW_DAY_COUNT)
        .map { row ->
            StepChartRow(
                startDate = row.date,
                label = row.date.format(DateTimeFormatter.ofPattern("M/d", Locale.US)),
                steps = row.steps,
                goalSteps = dailyGoal
            )
        }
}

private fun List<StepChartRow>.dropLeadingEmptyRows(): List<StepChartRow> =
    dropWhile { it.steps == 0L }

private fun compactSteps(steps: Long): String =
    when {
        steps >= 1_000_000 -> String.format(Locale.US, "%.1fm", steps / 1_000_000f)
        steps >= 10_000 -> "${steps / 1_000}k"
        steps >= 1_000 -> String.format(Locale.US, "%.1fk", steps / 1_000f)
        else -> steps.toString()
    }

private fun barFraction(steps: Long, maxSteps: Long): Float =
    if (steps <= 0L) {
        0f
    } else {
        (steps.toFloat() / maxSteps).coerceIn(0.16f, 1f)
    }

private data class WeightChartPoint(
    val time: Instant,
    val date: LocalDate,
    val endDate: LocalDate,
    val rawWeight: Double,
    val trailingAverage: Double
)

private data class DailyWeightPoint(
    val date: LocalDate,
    val weightKg: Double
)

private data class GroupedWeightPoint(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val weightKg: Double
)

private enum class WeightXAxisPeriod(val label: String, val days: Int) {
    Week("1W", 7),
    Month("1M", 30),
    ThreeMonths("3M", 90),
    SixMonths("6M", 180),
    Year("1Y", 365)
}

private enum class WeightChartGrouping(val label: String, val shortLabel: String) {
    Day("Day", "D"),
    Week("Week", "W"),
    Month("Month", "M")
}

private fun List<WeightHistoryRecord>.toWeightChartPoints(
    unitSystem: UnitSystem,
    grouping: WeightChartGrouping
): List<WeightChartPoint> {
    val zone = ZoneId.systemDefault()
    val dailyWeights = groupBy { it.time.atZone(zone).toLocalDate() }
        .toSortedMap()
        .map { (date, records) ->
            DailyWeightPoint(
                date = date,
                weightKg = records.map { it.weightKg }.average()
            )
        }

    val groupedWeights = dailyWeights.groupByWeightPeriod(grouping)
    return groupedWeights.map { groupedWeight ->
        val windowStart = groupedWeight.endDate.minusDays(6)
        val currentWindow = dailyWeights
            .filter { !it.date.isBefore(windowStart) && !it.date.isAfter(groupedWeight.endDate) }
        val trailingAverageKg = currentWindow
            .map { it.weightKg }
            .average()
            .takeUnless { it.isNaN() }
            ?: groupedWeight.weightKg
        WeightChartPoint(
            time = groupedWeight.startDate.atStartOfDay(zone).toInstant(),
            date = groupedWeight.startDate,
            endDate = groupedWeight.endDate,
            rawWeight = displayWeight(groupedWeight.weightKg, unitSystem),
            trailingAverage = displayWeight(trailingAverageKg, unitSystem)
        )
    }
}

private fun List<DailyWeightPoint>.groupByWeightPeriod(grouping: WeightChartGrouping): List<GroupedWeightPoint> =
    when (grouping) {
        WeightChartGrouping.Day -> map { dailyWeight ->
            GroupedWeightPoint(
                startDate = dailyWeight.date,
                endDate = dailyWeight.date,
                weightKg = dailyWeight.weightKg
            )
        }

        WeightChartGrouping.Week -> groupBy { dailyWeight ->
            dailyWeight.date.with(ChronoField.DAY_OF_WEEK, 1)
        }
            .toSortedMap()
            .map { (weekStart, weights) ->
                GroupedWeightPoint(
                    startDate = weekStart,
                    endDate = weights.maxOf { it.date },
                    weightKg = weights.map { it.weightKg }.average()
                )
            }

        WeightChartGrouping.Month -> groupBy { dailyWeight ->
            dailyWeight.date.withDayOfMonth(1)
        }
            .toSortedMap()
            .map { (monthStart, weights) ->
                GroupedWeightPoint(
                    startDate = monthStart,
                    endDate = weights.maxOf { it.date },
                    weightKg = weights.map { it.weightKg }.average()
                )
            }
    }

private fun displayWeight(weightKg: Double, unitSystem: UnitSystem): Double =
    if (unitSystem == UnitSystem.Imperial) {
        weightKg * KG_TO_LBS
    } else {
        weightKg
    }

private fun formatWeight(weight: Double, unitSystem: UnitSystem): String =
    String.format(Locale.US, "%.1f %s", weight, weightUnitLabel(unitSystem))

private fun formatSignedWeight(weight: Double, unitSystem: UnitSystem): String =
    String.format(Locale.US, "%+.1f %s", weight, weightUnitLabel(unitSystem))

private fun weightUnitLabel(unitSystem: UnitSystem): String =
    if (unitSystem == UnitSystem.Imperial) "lbs" else "kg"

private fun List<WeightChartPoint>.dateRangeLabel(): String {
    if (isEmpty()) return ""
    val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    val startDate = first().date
    val endDate = last().endDate
    return if (startDate.year == endDate.year) {
        "${startDate.format(formatter)} - ${endDate.format(formatter)}, ${endDate.year}"
    } else {
        "${startDate.format(formatter)}, ${startDate.year} - ${endDate.format(formatter)}, ${endDate.year}"
    }
}

private fun WeightChartPoint.dateLabel(): String =
    date.format(DateTimeFormatter.ofPattern("M/d", Locale.US))

private fun DrawScope.drawSmoothWeightLine(
    points: List<Offset>,
    color: Color,
    strokeWidth: Float
) {
    when {
        points.size < 2 -> return
        points.size == 2 -> drawLine(
            color = color,
            start = points.first(),
            end = points.last(),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        else -> {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (index in 0 until points.lastIndex) {
                    val p0 = points[(index - 1).coerceAtLeast(0)]
                    val p1 = points[index]
                    val p2 = points[index + 1]
                    val p3 = points[(index + 2).coerceAtMost(points.lastIndex)]
                    val control1X = p1.x + ((p2.x - p0.x) * WEIGHT_LINE_SMOOTHING)
                    val control1Y = p1.y + ((p2.y - p0.y) * WEIGHT_LINE_SMOOTHING)
                    val control2X = p2.x - ((p3.x - p1.x) * WEIGHT_LINE_SMOOTHING)
                    val control2Y = p2.y - ((p3.y - p1.y) * WEIGHT_LINE_SMOOTHING)
                    cubicTo(control1X, control1Y, control2X, control2Y, p2.x, p2.y)
                }
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

private fun formatWeightAxisDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ofPattern("M/d", Locale.US))

private fun WeightChartPoint.displayDateLabel(): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    return if (date == endDate) {
        "${date.format(formatter)}, ${date.year}"
    } else if (date.year == endDate.year) {
        "${date.format(formatter)} - ${endDate.format(formatter)}, ${endDate.year}"
    } else {
        "${date.format(formatter)}, ${date.year} - ${endDate.format(formatter)}, ${endDate.year}"
    }
}

private fun weightAxisLabels(minValue: Double, maxValue: Double): List<Double> {
    val range = (maxValue - minValue).coerceAtLeast(1.0)
    val step = (range / 4.0).coerceAtLeast(1.0)
    return (4 downTo 0).map { minValue + (step * it) }
}

private fun selectedWeightPointIndexForX(
    x: Float,
    width: Float,
    points: List<WeightChartPoint>,
    startTime: Long,
    timeRange: Long,
    leftPadding: Float,
    rightPadding: Float
): Int? {
    if (points.isEmpty()) return null
    val chartWidth = (width - leftPadding - rightPadding).coerceAtLeast(1f)
    val positionFraction = ((x - leftPadding) / chartWidth).coerceIn(0f, 1f)
    val selectedTime = startTime + (positionFraction * timeRange).toLong()
    return points.indices.minByOrNull { index ->
        kotlin.math.abs(points[index].time.toEpochMilli() - selectedTime)
    }
}

private data class WeightPinchScrollAnchor(
    val timestampMillis: Long,
    val viewportX: Float
)

private fun timestampAtViewportX(
    viewportX: Float,
    scrollOffsetPx: Float,
    startTime: Long,
    timeRange: Long,
    contentWidthPx: Float,
    leftPaddingPx: Float,
    rightPaddingPx: Float
): Long {
    val chartWidth = (contentWidthPx - leftPaddingPx - rightPaddingPx).coerceAtLeast(1f)
    val timestampFraction = ((scrollOffsetPx + viewportX - leftPaddingPx) / chartWidth).coerceIn(0f, 1f)
    return startTime + (timestampFraction * timeRange).toLong()
}

private fun scrollOffsetForTimestampAtViewportX(
    timestampMillis: Long,
    viewportX: Float,
    startTime: Long,
    timeRange: Long,
    viewportWidthPx: Float,
    contentWidthPx: Float,
    leftPaddingPx: Float,
    rightPaddingPx: Float
): Int {
    val chartWidth = (contentWidthPx - leftPaddingPx - rightPaddingPx).coerceAtLeast(1f)
    val maxScroll = (contentWidthPx - viewportWidthPx).coerceAtLeast(0f)
    val timestampFraction = ((timestampMillis - startTime).toFloat() / timeRange.toFloat()).coerceIn(0f, 1f)
    return ((timestampFraction * chartWidth) - viewportX + leftPaddingPx)
        .coerceIn(0f, maxScroll)
        .roundToInt()
}

private fun scrollOffsetForVisibleEndTime(
    endTimeMillis: Long,
    startTime: Long,
    timeRange: Long,
    viewportWidthPx: Float,
    contentWidthPx: Float,
    leftPaddingPx: Float,
    rightPaddingPx: Float
): Int {
    return scrollOffsetForTimestampAtViewportX(
        timestampMillis = endTimeMillis,
        viewportX = viewportWidthPx,
        startTime = startTime,
        timeRange = timeRange,
        viewportWidthPx = viewportWidthPx,
        contentWidthPx = contentWidthPx,
        leftPaddingPx = leftPaddingPx,
        rightPaddingPx = rightPaddingPx
    )
}

private data class VisibleWeightWindow(
    val points: List<WeightChartPoint>,
    val startTime: Long,
    val endTime: Long
)

private fun visibleWeightWindow(
    points: List<WeightChartPoint>,
    startTime: Long,
    timeRange: Long,
    scrollOffsetPx: Float,
    viewportWidthPx: Float,
    contentWidthPx: Float,
    leftPaddingPx: Float,
    rightPaddingPx: Float
): VisibleWeightWindow {
    if (points.isEmpty()) {
        return VisibleWeightWindow(emptyList(), startTime, startTime)
    }

    val chartWidth = (contentWidthPx - leftPaddingPx - rightPaddingPx).coerceAtLeast(1f)
    val visibleStartFraction = ((scrollOffsetPx - leftPaddingPx) / chartWidth).coerceIn(0f, 1f)
    val visibleEndFraction = ((scrollOffsetPx + viewportWidthPx - leftPaddingPx) / chartWidth).coerceIn(0f, 1f)
    val visibleStartTime = startTime + (visibleStartFraction * timeRange).toLong()
    val visibleEndTime = startTime + (visibleEndFraction * timeRange).toLong()
    val startIndex = points.indexOfLast { it.time.toEpochMilli() <= visibleStartTime }
        .coerceAtLeast(0)
    val endIndex = points.indexOfFirst { it.time.toEpochMilli() >= visibleEndTime }
        .let { if (it == -1) points.lastIndex else it }
        .coerceAtLeast(startIndex)

    return VisibleWeightWindow(
        points = points.subList(startIndex, endIndex + 1),
        startTime = visibleStartTime,
        endTime = visibleEndTime
    )
}

private fun formatVisibleWeightRange(visibleDays: Float): String {
    val roundedDays = visibleDays.roundToInt()
    return when {
        roundedDays >= 365 -> "1Y"
        roundedDays >= 60 -> "${roundedDays}D"
        else -> "${roundedDays.coerceAtLeast(1)}D"
    }
}

private fun matchingWeightXAxisPeriod(visibleDays: Float): WeightXAxisPeriod? =
    WeightXAxisPeriod.entries.firstOrNull { period ->
        kotlin.math.abs(visibleDays - period.days) < 0.5f
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
            averageSessionStepsPerMinute(session)?.let {
                StatLine("Avg steps/min", it.toString())
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

internal fun averageSessionStepsPerMinute(session: SessionEntity): Int? {
    val endTime = session.endTime ?: return null
    val elapsedSeconds = Duration.between(session.startTime, endTime).seconds
    if (elapsedSeconds <= 0) return null
    return (session.totalSteps / (elapsedSeconds / 60.0)).roundToInt().coerceAtLeast(0)
}

private fun heartRateSummary(storedValue: Int, fallbackValue: Double?): String {
    val value = if (storedValue > 0) storedValue else fallbackValue?.toInt() ?: 0
    return if (value > 0) "$value bpm" else "---"
}

private fun List<Int>.averageOrNull(): Double? =
    if (isEmpty()) null else average()

private const val KG_TO_LBS = 2.2046226218
private const val WEIGHT_CHART_PADDING = 1.0
private const val WEIGHT_LINE_SMOOTHING = 0.16f
private const val MILLIS_PER_DAY = 86_400_000.0
private const val AVERAGE_DAYS_PER_MONTH = 30.4375
private const val MIN_WEIGHT_VISIBLE_DAYS = 3f
private const val MAX_WEIGHT_VISIBLE_DAYS = 365f
private const val MIN_WEIGHT_PINCH_ZOOM = 0.80f
private const val MAX_WEIGHT_PINCH_ZOOM = 1.25f
private const val WEIGHT_PINCH_ZOOM_SENSITIVITY = 0.35f
private const val MIN_WEIGHT_PINCH_DELTA = 0.01f
private const val PREVIEW_DAY_COUNT = 7
private const val DAYS_PER_WEEK = 7L
private val STEP_CHART_BAR_SLOT_WIDTH = 42.dp
private val STEP_CHART_BAR_WIDTH = 32.dp
private val STEP_CHART_BAR_SPACING = 2.dp
private val STEP_CHART_EDGE_PADDING = 8.dp
private val STEP_CHART_GOAL_LABEL_WIDTH = 58.dp
private val STEP_CHART_X_AXIS_LABEL_HEIGHT = 22.dp
private val STEP_CHART_MIN_BAR_SLOT_WIDTH = 8.dp
private val STEP_CHART_MIN_BAR_WIDTH = 4.dp
