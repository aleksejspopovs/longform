package lv.popovs.longform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import lv.popovs.longform.ui.theme.LongformTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val showDisclosure = intent.getBooleanExtra(EXTRA_SHOW_ACCESSIBILITY_DISCLOSURE, false)
        setContent {
            LongformTheme {
                InstructionsScreen(initialShowAccessibilityDisclosure = showDisclosure)
            }
        }
    }

    companion object {
        const val EXTRA_SHOW_ACCESSIBILITY_DISCLOSURE = "show_accessibility_disclosure"
        private const val PREFS_NAME = "longform_prefs"
        private const val KEY_ACCESSIBILITY_ACCEPTED = "accessibility_disclosure_accepted"

        fun isAccessibilityDisclosureAccepted(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_ACCESSIBILITY_ACCEPTED, false)
        }

        fun setAccessibilityDisclosureAccepted(context: Context, accepted: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_ACCESSIBILITY_ACCEPTED, accepted).apply()
        }
    }
}

@Composable
fun InstructionsScreen(initialShowAccessibilityDisclosure: Boolean = false) {
    val context = LocalContext.current
    var hasNotificationPermission by remember { mutableStateOf(false) }
    var hasBluetoothPermissions by remember { mutableStateOf(false) }
    var hasLocationPermission by remember { mutableStateOf(false) }

    var showBluetoothDisclosure by remember { mutableStateOf(false) }
    var showAccessibilityDisclosure by remember { mutableStateOf(initialShowAccessibilityDisclosure) }

    val permissionsToRequest = mutableListOf<String>()
    if (!hasNotificationPermission) permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
    if (!hasBluetoothPermissions) {
        permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
        permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (!hasLocationPermission) {
        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            hasNotificationPermission = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: hasNotificationPermission
            hasBluetoothPermissions = (permissions[Manifest.permission.BLUETOOTH_SCAN] ?: hasBluetoothPermissions) &&
                                        (permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: hasBluetoothPermissions)
            hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: hasLocationPermission
        }
    )

    LaunchedEffect(Unit) {
        val notificationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val bluetoothScanPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val bluetoothConnectPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val locationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        hasNotificationPermission = notificationPermission
        hasBluetoothPermissions = bluetoothScanPermission && bluetoothConnectPermission
        hasLocationPermission = locationPermission
    }

    if (showBluetoothDisclosure) {
        AlertDialog(
            onDismissRequest = { showBluetoothDisclosure = false },
            title = { Text("Permissions Disclosure") },
            text = {
                Text("Longform uses Bluetooth and Location permissions to enable sending captured articles to your Xteink X4 device. We do not store your location or any other personal information, and we do not send any data over the internet.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBluetoothDisclosure = false
                    permissionLauncher.launch(permissionsToRequest.toTypedArray())
                }) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBluetoothDisclosure = false }) {
                    Text("Decline")
                }
            }
        )
    }

    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            title = { Text("Accessibility Disclosure") },
            text = {
                Text("Longform uses the AccessibilityService API to collect the contents of your screen and automatically scroll the screen on your behalf. This is required to capture long articles when you activate the Longform shortcut. You will be given the option to send the captured text to your Xteink X4 device or share it with another app, but we do not share or store your data otherwise. No data is sent over the internet by this app.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showAccessibilityDisclosure = false
                    MainActivity.setAccessibilityDisclosureAccepted(context, true)
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAccessibilityDisclosure = false
                    MainActivity.setAccessibilityDisclosureAccepted(context, false)
                }) {
                    Text("Decline")
                }
            }
        )
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Longform helps you extract text from long articles.",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = "1. Grant permissions.\n2. Go to Accessibility settings and assign a shortcut to 'Longform'.\n3. Open an article in your browser or news app.\n4. Activate the shortcut. The app will scroll and capture the text automatically.\n5. Tap the notification to view the captured text and send it to another app or device.",
                modifier = Modifier.padding(vertical = 24.dp)
            )

            if (permissionsToRequest.isNotEmpty()) {
                Button(onClick = {
                    if (!hasBluetoothPermissions || !hasLocationPermission) {
                        showBluetoothDisclosure = true
                    } else {
                        permissionLauncher.launch(permissionsToRequest.toTypedArray())
                    }
                }) {
                    Text("Grant Permissions")
                }
            }

            Button(
                onClick = {
                    if (MainActivity.isAccessibilityDisclosureAccepted(context)) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } else {
                        showAccessibilityDisclosure = true
                    }
                },
                enabled = hasNotificationPermission && hasBluetoothPermissions && hasLocationPermission
            ) {
                Text("Open Accessibility Settings")
            }
        }
    }
}
