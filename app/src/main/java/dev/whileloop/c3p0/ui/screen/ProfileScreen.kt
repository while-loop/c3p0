package dev.whileloop.c3p0.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.whileloop.c3p0.data.model.UnitSystem
import dev.whileloop.c3p0.ui.permission.PermissionGuidanceBottomSheet
import dev.whileloop.c3p0.ui.permission.PermissionRequestKind
import dev.whileloop.c3p0.ui.permission.healthConnectPermissions
import dev.whileloop.c3p0.ui.permission.permissionGuidance
import dev.whileloop.c3p0.ui.viewmodel.ProfileViewModel

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = hiltViewModel(),
    onNavigateToPairing: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val stepGoal by viewModel.stepGoal.collectAsState()
    val age by viewModel.age.collectAsState()
    val isHealthConnectEnabled by viewModel.isHealthConnectEnabled.collectAsState()
    val isGoogleDriveSyncEnabled by viewModel.isGoogleDriveSyncEnabled.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()
    val skipInactiveDeviceWarning by viewModel.skipInactiveDeviceWarning.collectAsState()
    val bodyWeightKg by viewModel.bodyWeightKg.collectAsState()
    val isRefreshingWeight by viewModel.isRefreshingWeight.collectAsState()
    val treadmillAddress by viewModel.treadmillAddress.collectAsState()
    val watchAddress by viewModel.watchAddress.collectAsState()
    val treadmillConnectionState by viewModel.treadmillConnectionState.collectAsState()
    val watchConnectionState by viewModel.watchConnectionState.collectAsState()
    val healthConnectPermissions = remember { healthConnectPermissions() }
    var showHealthConnectPermissionSheet by remember { mutableStateOf(false) }
    var launchHealthConnectPermissions by remember { mutableStateOf(false) }
    val healthConnectPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val enabled = granted.containsAll(healthConnectPermissions)
        viewModel.toggleHealthConnect(enabled)
        if (enabled) {
            viewModel.refreshWeightFromHealthConnect()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshHealthConnectPermissionState()
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshHealthConnectPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(launchHealthConnectPermissions) {
        if (!launchHealthConnectPermissions) return@LaunchedEffect

        launchHealthConnectPermissions = false
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                healthConnectPermissionLauncher.launch(healthConnectPermissions)
            }

            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                openHealthConnectStorePage(context)
                viewModel.toggleHealthConnect(false)
            }

            else -> {
                Toast.makeText(
                    context,
                    "Health Connect is not available on this device.",
                    Toast.LENGTH_LONG
                ).show()
                viewModel.toggleHealthConnect(false)
            }
        }
    }

    if (showHealthConnectPermissionSheet) {
        PermissionGuidanceBottomSheet(
            guidance = permissionGuidance(PermissionRequestKind.HealthConnect),
            onContinue = {
                showHealthConnectPermissionSheet = false
                launchHealthConnectPermissions = true
            },
            onDismiss = {
                showHealthConnectPermissionSheet = false
                viewModel.toggleHealthConnect(false)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Profile Settings", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Devices", style = MaterialTheme.typography.titleMedium)
        ListItem(
            headlineContent = { Text("Pair WalkingPad / Watch") },
            supportingContent = { Text("Connect your devices") },
            trailingContent = {
                Button(onClick = onNavigateToPairing) {
                    Text("Pair")
                }
            }
        )
        ListItem(
            headlineContent = { Text("WalkingPad") },
            supportingContent = {
                Text(treadmillAddress ?: "No device selected")
            },
            trailingContent = {
                Text(viewModel.connectionLabel(treadmillConnectionState))
            }
        )
        ListItem(
            headlineContent = { Text("Watch") },
            supportingContent = {
                Text(watchAddress ?: "No device selected")
            },
            trailingContent = {
                Text(viewModel.connectionLabel(watchConnectionState))
            }
        )
        // TODO: Re-enable no-load stop controls after capturing the KS Fit BLE packet/key.

        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Integrations", style = MaterialTheme.typography.titleMedium)
        ListItem(
            headlineContent = { Text("Health Connect") },
            supportingContent = {
                Text(
                    bodyWeightKg?.let { "Sync training sessions. Weight: ${formatWeight(it, unitSystem)}" }
                        ?: "Sync training sessions. Weight not synced"
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            if (isHealthConnectEnabled) {
                                viewModel.refreshWeightFromHealthConnect()
                            } else {
                                showHealthConnectPermissionSheet = true
                            }
                        },
                        enabled = !isRefreshingWeight
                    ) {
                        if (isRefreshingWeight) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Health Connect weight")
                        }
                    }
                    Switch(
                        checked = isHealthConnectEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                showHealthConnectPermissionSheet = true
                            } else {
                                Toast.makeText(
                                    context,
                                    "Turn off C3P0 access in Health Connect settings.",
                                    Toast.LENGTH_LONG
                                ).show()
                                openHealthConnectSettings(context)
                            }
                        }
                    )
                }
            }
        )
        ListItem(
            headlineContent = { Text("Google Drive") },
            supportingContent = {
                Text(
                    if (isGoogleDriveSyncEnabled) {
                        "Android backup is enabled. Changes request a cloud backup."
                    } else {
                        "Backup app data to cloud"
                    }
                )
            },
            trailingContent = {
                Switch(
                    checked = isGoogleDriveSyncEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.toggleGoogleDriveSync(enabled)
                        Toast.makeText(
                            context,
                            if (enabled) {
                                "Backup requested. Android will upload it when backup runs."
                            } else {
                                "Backup requests disabled."
                            },
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text("Display", style = MaterialTheme.typography.titleMedium)
        ListItem(
            headlineContent = { Text("Units") },
            supportingContent = { Text(if (unitSystem == UnitSystem.Metric) "Kilometers and km/h" else "Miles and mph") }
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = unitSystem == UnitSystem.Metric,
                onClick = { viewModel.updateUnitSystem(UnitSystem.Metric) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                modifier = Modifier.weight(1f)
            ) {
                Text("Metric")
            }
            SegmentedButton(
                selected = unitSystem == UnitSystem.Imperial,
                onClick = { viewModel.updateUnitSystem(UnitSystem.Imperial) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier = Modifier.weight(1f)
            ) {
                Text("Imperial")
            }
        }
        ListItem(
            headlineContent = { Text("Inactive device warning") },
            supportingContent = { Text("Warn before starting if the pad or watch is not active") },
            trailingContent = {
                Switch(
                    checked = !skipInactiveDeviceWarning,
                    onCheckedChange = { enabled -> viewModel.updateSkipInactiveDeviceWarning(!enabled) }
                )
            }
        )

        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Goals", style = MaterialTheme.typography.titleMedium)
        ListItem(
            headlineContent = { Text("Daily Step Goal") },
            supportingContent = { Text("$stepGoal steps") },
            trailingContent = {
                Row {
                    IconButton(onClick = { viewModel.updateStepGoal(stepGoal - 500) }) {
                        Text("-")
                    }
                    IconButton(onClick = { viewModel.updateStepGoal(stepGoal + 500) }) {
                        Text("+")
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text("Bio", style = MaterialTheme.typography.titleMedium)
        ListItem(
            headlineContent = { Text("Age") },
            supportingContent = { Text("$age years") },
            trailingContent = {
                Row {
                    IconButton(onClick = { viewModel.updateAge(age - 1) }) {
                        Text("-")
                    }
                    IconButton(onClick = { viewModel.updateAge(age + 1) }) {
                        Text("+")
                    }
                }
            }
        )
        
        val maxHr = 220 - age
        val zone2Min = (maxHr * 0.6).toInt()
        val zone2Max = (maxHr * 0.7).toInt()
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("Target Zone 2 HR: $zone2Min - $zone2Max bpm", style = MaterialTheme.typography.bodyMedium)
    }
}

private fun openHealthConnectStorePage(context: android.content.Context) {
    val playStoreIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=com.google.android.apps.healthdata")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val webIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching {
        context.startActivity(playStoreIntent)
    }.recoverCatching {
        context.startActivity(webIntent)
    }
}

private fun openHealthConnectSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(healthConnectSettingsAction())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun healthConnectSettingsAction(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        "android.health.connect.action.HEALTH_HOME_SETTINGS"
    } else {
        "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"
    }

private fun formatWeight(weightKg: Double, unitSystem: UnitSystem): String =
    if (unitSystem == UnitSystem.Imperial) {
        String.format(java.util.Locale.US, "%.0f lb", weightKg * 2.2046226218)
    } else {
        String.format(java.util.Locale.US, "%.1f kg", weightKg)
    }
