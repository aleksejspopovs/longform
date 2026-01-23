package lv.popovs.longform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lv.popovs.longform.ui.theme.LongformTheme

class SendToEinkActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager
    private var fileUri: Uri? = null
    private var fileName: String? = null
    private var fileBytes: ByteArray? = null

    companion object {
        private const val TAG = "SendToEinkActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND) {
            if (intent.type?.startsWith("text/") == true) {
                fileName = (intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Note") + ".txt"
                fileBytes = intent.getStringExtra(Intent.EXTRA_TEXT)?.toByteArray()
            } else {
                fileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                fileName = fileUri?.let { getFileName(it) } ?: fileUri?.lastPathSegment
                fileBytes = fileUri?.let { contentResolver.openInputStream(it)?.readBytes() }
            }
            fileName = fileName ?: "Note.txt";
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            // Device doesn't support Bluetooth
            // TODO: Handle this case
            return
        }

        bleManager = BleManager(this, bluetoothAdapter)

        setContent {
            LongformTheme {
                SendToEinkScreen(bleManager, fileName) { device ->
                    fileBytes?.let {
                        bleManager.sendFile(device, fileName ?: "article.txt", it)
                    }
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                // The DISPLAY_NAME column is a common convention for content providers
                // to store the original file name.
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    return it.getString(nameIndex)
                }
            }
        }
        return null
    }
}

@Composable
fun SendToEinkScreen(bleManager: BleManager, fileName: String?, onDeviceClick: (ScanResult) -> Unit) {
    val scannedDevices by bleManager.scannedDevices.collectAsState()
    val transferStatus by bleManager.transferStatus.collectAsState()

    LaunchedEffect(Unit) {
        bleManager.startScan()
    }

    DisposableEffect(Unit) {
        onDispose {
            bleManager.stopScan()
        }
    }

    Scaffold {
            innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
            Text(
                text = "Select a device to send ${fileName ?: "the file"} to:",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Text(transferStatus)
            if (scannedDevices.isEmpty()) {
                Text("No devices found yet...")
            } else {
                DeviceList(devices = scannedDevices, onDeviceClick = onDeviceClick)
            }
        }
    }
}

@Composable
fun DeviceList(devices: List<ScanResult>, onDeviceClick: (ScanResult) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(devices) { device ->
            @SuppressLint("MissingPermission")
            val deviceName = device.device.name ?: "Unknown"
            val macAddress = device.device.address
            Text(
                text = "$deviceName ($macAddress)",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDeviceClick(device) }
                    .padding(8.dp)
            )
        }
    }
}