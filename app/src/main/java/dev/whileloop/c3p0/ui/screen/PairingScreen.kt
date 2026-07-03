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
import dev.whileloop.c3p0.ui.permission.PermissionGuidanceBottomSheet
import dev.whileloop.c3p0.ui.permission.PermissionRequestKind
import dev.whileloop.c3p0.ui.permission.bleScanPermissions
import dev.whileloop.c3p0.ui.permission.hasPermissions
import dev.whileloop.c3p0.ui.permission.permissionGuidance
import dev.whileloop.c3p0.ui.viewmodel.PairingViewModel

@Composable
fun PairingScreen(
    viewModel: PairingViewModel = hiltViewModel(),
    onDevicePaired: () -> Unit
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    viewModel.treadmillAddress.collectAsState()
    viewModel.watchAddress.collectAsState()
    viewModel.treadmillConnectionState.collectAsState()
    viewModel.watchConnectionState.collectAsState()
    viewModel.pairingAddress.collectAsState()
    var showPermissionSheet by remember { mutableStateOf(false) }
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pair Devices", style = MaterialTheme.typography.headlineMedium)
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
            items(devices.sortedByDescending { it.rssi }) { device ->
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
                                Text(if (connectionLabel == "Connected") "Active" else connectionLabel)
                            }
                        }
                    },
                    modifier = Modifier.clickable {
                        viewModel.pairDevice(device)
                    }
                )
                HorizontalDivider()
            }
        }
    }
}
