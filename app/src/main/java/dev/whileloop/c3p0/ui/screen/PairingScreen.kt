package dev.whileloop.c3p0.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.whileloop.c3p0.ui.viewmodel.PairingViewModel

@Composable
fun PairingScreen(
    viewModel: PairingViewModel = hiltViewModel(),
    onDevicePaired: () -> Unit
) {
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pair Devices", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.startScan() },
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isScanning) "Scanning..." else "Scan for Devices")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices.toList()) { device ->
                ListItem(
                    headlineContent = { Text(device.name ?: "Unknown Device") },
                    supportingContent = { Text(device.address) },
                    trailingContent = { Text("${device.rssi} dBm") },
                    modifier = Modifier.clickable {
                        viewModel.pairDevice(device)
                        onDevicePaired()
                    }
                )
                HorizontalDivider()
            }
        }
    }
}
