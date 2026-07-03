package dev.whileloop.c3p0.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
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
    val stepGoal by viewModel.stepGoal.collectAsState()
    val age by viewModel.age.collectAsState()
    val isHealthConnectEnabled by viewModel.isHealthConnectEnabled.collectAsState()
    val isGoogleDriveSyncEnabled by viewModel.isGoogleDriveSyncEnabled.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()
    val skipInactiveDeviceWarning by viewModel.skipInactiveDeviceWarning.collectAsState()
    val healthConnectPermissions = remember { healthConnectPermissions() }
    var showHealthConnectPermissionSheet by remember { mutableStateOf(false) }
    val healthConnectPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        viewModel.toggleHealthConnect(granted.containsAll(healthConnectPermissions))
    }

    if (showHealthConnectPermissionSheet) {
        PermissionGuidanceBottomSheet(
            guidance = permissionGuidance(PermissionRequestKind.HealthConnect),
            onContinue = {
                showHealthConnectPermissionSheet = false
                healthConnectPermissionLauncher.launch(healthConnectPermissions)
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

        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Integrations", style = MaterialTheme.typography.titleMedium)
        ListItem(
            headlineContent = { Text("Health Connect") },
            supportingContent = { Text("Sync training sessions") },
            trailingContent = {
                Switch(
                    checked = isHealthConnectEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            showHealthConnectPermissionSheet = true
                        } else {
                            viewModel.toggleHealthConnect(false)
                        }
                    }
                )
            }
        )
        ListItem(
            headlineContent = { Text("Google Drive") },
            supportingContent = { Text("Backup app data to cloud") },
            trailingContent = {
                Switch(checked = isGoogleDriveSyncEnabled, onCheckedChange = { viewModel.toggleGoogleDriveSync(it) })
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
                // Simplified slider or buttons for now
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
