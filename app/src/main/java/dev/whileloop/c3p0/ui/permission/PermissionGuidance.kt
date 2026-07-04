package dev.whileloop.c3p0.ui.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.whileloop.c3p0.health.HealthConnectManager

enum class PermissionRequestKind {
    BleScan,
    Session,
    HealthConnect,
    HealthConnectSteps
}

data class PermissionGuidance(
    val title: String,
    val body: String,
    val actionLabel: String
)

fun bleScanPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun sessionPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

fun healthConnectPermissions(): Set<String> = HealthConnectManager.PERMISSIONS

fun healthConnectStepHistoryPermissions(): Set<String> = HealthConnectManager.STEP_HISTORY_PERMISSIONS

fun Context.hasPermissions(permissions: Array<String>): Boolean =
    permissions.all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

fun permissionGuidance(kind: PermissionRequestKind): PermissionGuidance =
    when (kind) {
        PermissionRequestKind.BleScan -> PermissionGuidance(
            title = "Scan for nearby devices",
            body = "C3P0 needs Bluetooth permission to find your WalkingPad and heart-rate devices. On older Android versions, nearby Bluetooth scans are grouped with location permission.",
            actionLabel = "Continue"
        )

        PermissionRequestKind.Session -> PermissionGuidance(
            title = "Start a walking session",
            body = "C3P0 needs Bluetooth access to control the connected device and notification permission to keep the active session visible while it runs.",
            actionLabel = "Continue"
        )

        PermissionRequestKind.HealthConnect -> PermissionGuidance(
            title = "Sync with Health Connect",
            body = "C3P0 needs Health Connect access before it can read steps and body weight or write completed walking sessions.",
            actionLabel = "Continue"
        )

        PermissionRequestKind.HealthConnectSteps -> PermissionGuidance(
            title = "Read step history",
            body = "C3P0 needs Health Connect step access to show historical totals and exclude other apps' steps during C3P0 sessions.",
            actionLabel = "Continue"
        )
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionGuidanceBottomSheet(
    guidance: PermissionGuidance,
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
            Text(guidance.title, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            Text(guidance.body, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(guidance.actionLabel)
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Not now")
            }
        }
    }
}
