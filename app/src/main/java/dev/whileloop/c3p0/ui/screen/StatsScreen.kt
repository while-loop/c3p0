package dev.whileloop.c3p0.ui.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import dev.whileloop.c3p0.data.entity.SessionEntity
import dev.whileloop.c3p0.data.entity.SessionMetricEntity
import dev.whileloop.c3p0.data.model.UnitSystem
import dev.whileloop.c3p0.domain.usecase.DailyStepHistory
import dev.whileloop.c3p0.domain.usecase.NormalizedStepsResult
import dev.whileloop.c3p0.ui.permission.PermissionGuidanceBottomSheet
import dev.whileloop.c3p0.ui.permission.PermissionRequestKind
import dev.whileloop.c3p0.ui.permission.healthConnectStepHistoryPermissions
import dev.whileloop.c3p0.ui.permission.permissionGuidance
import dev.whileloop.c3p0.ui.viewmodel.StatsViewModel
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoField
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun StatsScreen(
    viewModel: StatsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val sessions by viewModel.allSessions.collectAsState()
    val selectedSession by viewModel.selectedSession.collectAsState()
    val metrics by viewModel.selectedSessionMetrics.collectAsState()
    val normalizedSteps by viewModel.normalizedSteps.collectAsState()
    val dailyStepHistory by viewModel.dailyStepHistory.collectAsState()
    val canReadHealthConnectSteps by viewModel.canReadHealthConnectSteps.collectAsState()
    val isStepHistoryLoading by viewModel.isStepHistoryLoading.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()
    var showStepPermissionSheet by remember { mutableStateOf(false) }
    var stepChartPeriod by remember { mutableStateOf(StepChartPeriod.Day) }
    val stepHistoryPermissions = remember { healthConnectStepHistoryPermissions() }
    val healthConnectPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        viewModel.refreshStepHistory()
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
                HealthConnectStepHistoryCard(
                    rows = dailyStepHistory,
                    canReadSteps = canReadHealthConnectSteps,
                    isLoading = isStepHistoryLoading,
                    selectedPeriod = stepChartPeriod,
                    onPeriodSelected = { stepChartPeriod = it },
                    onEnable = { showStepPermissionSheet = true },
                    onRefresh = { viewModel.refreshStepHistory() },
                    modifier = Modifier
                        .fillParentMaxHeight(0.42f)
                        .heightIn(min = 320.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            selectedSession?.let { session ->
                item {
                    SessionDetailCard(session, metrics, normalizedSteps, unitSystem)
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
private fun HealthConnectStepHistoryCard(
    rows: List<DailyStepHistory>,
    canReadSteps: Boolean,
    isLoading: Boolean,
    selectedPeriod: StepChartPeriod,
    onPeriodSelected: (StepChartPeriod) -> Unit,
    onEnable: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chartRows = remember(rows, selectedPeriod) { rows.toStepChartRows(selectedPeriod) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Health Connect steps", style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = if (canReadSteps) onRefresh else onEnable,
                    enabled = !isLoading,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text(if (canReadSteps) "Refresh" else "Enable")
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                StepChartPeriod.entries.forEachIndexed { index, period ->
                    SegmentedButton(
                        selected = selectedPeriod == period,
                        onClick = { onPeriodSelected(period) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = StepChartPeriod.entries.size
                        )
                    ) {
                        Text(period.label)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (canReadSteps && rows.isNotEmpty()) {
                StepChartLegend()
                Spacer(modifier = Modifier.height(6.dp))
            }
            when {
                isLoading -> Text("Loading step history...")
                !canReadSteps -> Text("Enable Health Connect step access to view raw and normalized historical steps.")
                rows.isEmpty() -> Text("No Health Connect step data found.")
                chartRows.isEmpty() -> Text("No grouped step data found.")
                else -> HealthConnectStepChart(
                    rows = chartRows,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HealthConnectStepChart(
    rows: List<StepChartRow>,
    modifier: Modifier = Modifier
) {
    val maxSteps = rows.maxOfOrNull { it.rawSteps }?.coerceAtLeast(1L) ?: 1L
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (rows.size - DEFAULT_VISIBLE_CHART_BARS).coerceAtLeast(0)
    )
    LaunchedEffect(rows.size) {
        listState.scrollToItem((rows.size - DEFAULT_VISIBLE_CHART_BARS).coerceAtLeast(0))
    }
    val rawBarColor = MaterialTheme.colorScheme.primaryContainer
    val normalizedBarColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    LazyRow(
        state = listState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(rows) { row ->
            val rawFraction = barFraction(row.rawSteps, maxSteps)
            val normalizedFraction = barFraction(row.normalizedSteps, maxSteps).coerceAtMost(rawFraction)
            Column(
                modifier = Modifier
                    .width(62.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .width(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(trackColor),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(rawFraction)
                            .clip(RoundedCornerShape(6.dp))
                            .background(rawBarColor)
                            .align(Alignment.BottomCenter),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        StepBarValueLabel(
                            text = compactSteps(row.rawSteps),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .fillMaxHeight(normalizedFraction)
                            .clip(RoundedCornerShape(6.dp))
                            .background(normalizedBarColor)
                            .align(Alignment.BottomCenter),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        StepBarValueLabel(
                            text = compactSteps(row.normalizedSteps),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun StepBarValueLabel(
    text: String,
    color: androidx.compose.ui.graphics.Color
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp)
    )
}

@Composable
private fun StepChartLegend() {
    val rawBarColor = MaterialTheme.colorScheme.primaryContainer
    val normalizedBarColor = MaterialTheme.colorScheme.primary
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(color = rawBarColor, label = "Raw")
        LegendItem(color = normalizedBarColor, label = "Normalized")
    }
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

private enum class StepChartPeriod(val label: String) {
    Day("Day"),
    Week("Week"),
    Month("Month")
}

private data class StepChartRow(
    val startDate: LocalDate,
    val label: String,
    val rawSteps: Long,
    val normalizedSteps: Long
)

private fun List<DailyStepHistory>.toStepChartRows(period: StepChartPeriod): List<StepChartRow> {
    val chronological = sortedBy { it.date }
    return when (period) {
        StepChartPeriod.Day -> chronological.map { row ->
            StepChartRow(
                startDate = row.date,
                label = row.date.format(DateTimeFormatter.ofPattern("M/d", Locale.US)),
                rawSteps = row.rawSteps,
                normalizedSteps = row.normalizedSteps
            )
        }
        StepChartPeriod.Week -> chronological
            .groupBy { row ->
                row.date.with(ChronoField.DAY_OF_WEEK, 1)
            }
            .toSortedMap()
            .map { (weekStart, weekRows) ->
                StepChartRow(
                    startDate = weekStart,
                    label = weekStart.format(DateTimeFormatter.ofPattern("M/d", Locale.US)),
                    rawSteps = weekRows.sumOf { it.rawSteps },
                    normalizedSteps = weekRows.sumOf { it.normalizedSteps }
                )
            }
        StepChartPeriod.Month -> chronological
            .groupBy { row -> row.date.withDayOfMonth(1) }
            .toSortedMap()
            .map { (monthStart, monthRows) ->
                StepChartRow(
                    startDate = monthStart,
                    label = monthStart.format(DateTimeFormatter.ofPattern("MMM", Locale.US)),
                    rawSteps = monthRows.sumOf { it.rawSteps },
                    normalizedSteps = monthRows.sumOf { it.normalizedSteps }
                )
            }
    }
}

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

private const val DEFAULT_VISIBLE_CHART_BARS = 7
