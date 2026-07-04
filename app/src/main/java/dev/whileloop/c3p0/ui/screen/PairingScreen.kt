package dev.whileloop.c3p0.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.whileloop.c3p0.ble.model.BleDevice
import dev.whileloop.c3p0.ui.permission.PermissionGuidanceBottomSheet
import dev.whileloop.c3p0.ui.permission.PermissionRequestKind
import dev.whileloop.c3p0.ui.permission.bleScanPermissions
import dev.whileloop.c3p0.ui.permission.hasPermissions
import dev.whileloop.c3p0.ui.permission.permissionGuidance
import dev.whileloop.c3p0.ui.viewmodel.PairingDeviceRole
import dev.whileloop.c3p0.ui.viewmodel.PairingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    viewModel: PairingViewModel = hiltViewModel(),
    onDevicePaired: () -> Unit
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val treadmillAddress by viewModel.treadmillAddress.collectAsState()
    val watchAddress by viewModel.watchAddress.collectAsState()
    viewModel.treadmillConnectionState.collectAsState()
    viewModel.watchConnectionState.collectAsState()
    viewModel.pairingAddress.collectAsState()
    var showPermissionSheet by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<BleDevice?>(null) }
    val scanPermissions = remember { bleScanPermissions() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.startScan()
        }
    }

    if (showPermissionSheet) {
        PermissionGuidanceBottomSheet(
            guidance = permissionGuidance(PermissionRequestKind.BleScan),
            onContinue = {
                showPermissionSheet = false
                permissionLauncher.launch(scanPermissions)
            },
            onDismiss = { showPermissionSheet = false }
        )
    }

    selectedDevice?.let { device ->
        DeviceRoleBottomSheet(
            device = device,
            onRoleSelected = { role ->
                selectedDevice = null
                viewModel.pairDevice(device, role)
            },
            onDismiss = { selectedDevice = null }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Pair Devices", style = MaterialTheme.typography.headlineMedium)
            if (treadmillAddress != null || watchAddress != null) {
                TextButton(onClick = onDevicePaired) {
                    Text("Done")
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (context.hasPermissions(scanPermissions)) {
                    viewModel.startScan()
                } else {
                    showPermissionSheet = true
                }
            },
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isScanning) "Scanning..." else "Scan for Devices")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices) { device ->
                val connectionLabel = viewModel.deviceConnectionLabel(device)
                ListItem(
                    headlineContent = { Text(device.name ?: "Unknown Device") },
                    supportingContent = {
                        Column {
                            Text(device.address)
                            if (connectionLabel != null) {
                                Text(connectionLabel, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    trailingContent = {
                        Column {
                            Text("${device.rssi} dBm")
                            if (connectionLabel != null) {
                                Text(connectionLabel)
                            }
                        }
                    },
                    modifier = Modifier.clickable {
                        selectedDevice = device
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceRoleBottomSheet(
    device: BleDevice,
    onRoleSelected: (PairingDeviceRole) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Device type", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = device.name ?: "Unknown Device",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedButton(
                onClick = { onRoleSelected(PairingDeviceRole.Pad) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("WalkingPad")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onRoleSelected(PairingDeviceRole.Watch) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Watch")
            }
        }
    }
}
