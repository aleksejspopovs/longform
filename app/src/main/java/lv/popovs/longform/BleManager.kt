package lv.popovs.longform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context, private val bluetoothAdapter: BluetoothAdapter) {

    private val _scannedDevices = MutableStateFlow<List<ScanResult>>(emptyList())
    val scannedDevices = _scannedDevices.asStateFlow()

    private val _transferStatus = MutableStateFlow("")
    val transferStatus = _transferStatus.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var fileToSend: ByteArray? = null
    private var fileName: String? = null
    private var transferInProgress = false
    private var bytesSent = 0
    private var mtu = 23 // Default MTU
    private val txnId = (1..Int.MAX_VALUE).random()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _transferStatus.value = "Requesting MTU..."
                gatt?.requestMtu(517) // Android actually ignores this value
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _transferStatus.value = "Disconnected"
                disconnect()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                this@BleManager.mtu = mtu
                _transferStatus.value = "Discovering services..."
                gatt?.discoverServices()
            } else {
                _transferStatus.value = "MTU request failed"
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val characteristic = gatt?.getService(SERVICE_UUID)?.getCharacteristic(SERVER_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    _transferStatus.value = "Server characteristic not found"
                    disconnect()
                    return
                }

                // 1. Enable notifications locally
                if (!gatt.setCharacteristicNotification(characteristic, true)) {
                    _transferStatus.value = "Failed to enable local notifications"
                    disconnect()
                    return
                }

                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                if (descriptor == null) {
                    _transferStatus.value = "CCCD not found"
                    disconnect()
                    return
                }

                // 2. Write to the descriptor to enable indications on the peripheral
                _transferStatus.value = "Enabling indications..."
                // Use the modern API (API 33+) for writing descriptors
                // We rely on onDescriptorWrite for the result.
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            } else {
                _transferStatus.value = "Service discovery failed"
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _transferStatus.value = "Sending offer..."
                sendOffer(gatt)
            } else {
                _transferStatus.value = "Failed to enable indications (status: $status)"
                disconnect()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (transferInProgress) {
                // This call ensures the next chunk is only sent after the previous write has been acknowledged (due to WRITE_TYPE_DEFAULT)
                sendFileChunk(gatt)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.remaining() < 12) return
            val messageType = buffer.int
            val receivedTxnId = buffer.int
            if (receivedTxnId == txnId && messageType == 1) { // server_response
                val status = buffer.int
                if (status == 0) {
                    _transferStatus.value = "Sending file..."
                    transferInProgress = true
                    sendFileChunk(gatt)
                } else {
                    _transferStatus.value = "Error #$status occurred"
                }
            }
        }
    }

    private fun sendOffer(gatt: BluetoothGatt?) {
        val characteristic = gatt?.getService(SERVICE_UUID)?.getCharacteristic(CLIENT_CHARACTERISTIC_UUID) ?: return
        val originalFileName = fileName ?: return
        val fileBytes = fileToSend ?: return

        // MTU - 5 is the max payload size for a characteristic write.
        // The fixed header is 5x 4-byte Ints = 20 bytes.
        val maxFileNameBytesSize = (mtu - 5) - 20

        val originalFileNameBytes = originalFileName.toByteArray(Charsets.UTF_8)
        var actualFileNameBytes = originalFileNameBytes

        if (originalFileNameBytes.size > maxFileNameBytesSize) {
            val lastDotIndex = originalFileName.lastIndexOf('.')
            val extension = if (lastDotIndex == -1) "" else originalFileName.substring(lastDotIndex)
            val baseName = if (lastDotIndex == -1) originalFileName else originalFileName.substring(0, lastDotIndex)

            val extensionBytesSize = extension.toByteArray(Charsets.UTF_8).size
            val maxBaseNameBytesSize = maxFileNameBytesSize - extensionBytesSize

            if (maxBaseNameBytesSize <= 0) {
                // If the extension is too long or the remaining space is zero/negative, just hard-truncate the whole name.
                actualFileNameBytes = originalFileNameBytes.sliceArray(0 until maxFileNameBytesSize)
                Log.w(TAG, "Filename and/or extension too long. Hard truncating to ${actualFileNameBytes.size} bytes.")
            } else {
                // Truncate the base name character by character until its byte size is safe
                var newBaseName = baseName
                while (newBaseName.toByteArray(Charsets.UTF_8).size > maxBaseNameBytesSize && newBaseName.isNotEmpty()) {
                    newBaseName = newBaseName.dropLast(1)
                }

                val truncatedFileName = newBaseName + extension
                actualFileNameBytes = truncatedFileName.toByteArray(Charsets.UTF_8)

                Log.w(TAG, "Filename too long (${originalFileNameBytes.size} bytes). Truncating base name to fit. Final name: $truncatedFileName (${actualFileNameBytes.size} bytes)")
            }
        }

        val buffer = ByteBuffer.allocate(20 + actualFileNameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0) // message_type: offer
        buffer.putInt(txnId)
        buffer.putInt(1) // protocol_version
        buffer.putInt(fileBytes.size) // total_file_length
        buffer.putInt(actualFileNameBytes.size) // file_name_length (reflects the potentially truncated size)
        buffer.put(actualFileNameBytes)

        // Use the modern API for characteristic writes (API 33+)
        gatt.writeCharacteristic(
            characteristic,
            buffer.array(),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
    }

    private fun sendFileChunk(gatt: BluetoothGatt?) {
        val characteristic = gatt?.getService(SERVICE_UUID)?.getCharacteristic(CLIENT_CHARACTERISTIC_UUID) ?: return
        val fileBytes = fileToSend ?: return

        if (bytesSent >= fileBytes.size) {
            _transferStatus.value = "File sent successfully"
            transferInProgress = false
            disconnect()
            return
        }

        val chunkSize = mtu - 5 - 12
        val remainingBytes = fileBytes.size - bytesSent
        val currentChunkSize = if (chunkSize > remainingBytes) remainingBytes else chunkSize

        val buffer = ByteBuffer.allocate(12 + currentChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(2)
        buffer.putInt(txnId)
        buffer.putInt(bytesSent)
        buffer.put(fileBytes, bytesSent, currentChunkSize)

        9.d(TAG, "mtu $mtu, offset $bytesSent, chunk size $chunkSize, remaining bytes $remainingBytes, current chunk size $currentChunkSize, buffer size ${buffer.array().size}")

        // Use the modern API for characteristic writes (API 33+)
        // This is a sequential write, using the default type which is generally WITH_RESPONSE.
        gatt.writeCharacteristic(
            characteristic,
            buffer.array(),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )

        bytesSent += currentChunkSize
        _transferStatus.value = "Sending file... (${(bytesSent * 100) / fileBytes.size}%)"
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (_scannedDevices.value.none { it.device.address == result.device.address }) {
                _scannedDevices.value = _scannedDevices.value + result
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { result ->
                if (_scannedDevices.value.none { it.device.address == result.device.address }) {
                    _scannedDevices.value = _scannedDevices.value + result
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "onScanFailed: errorCode $errorCode")
            _transferStatus.value = "Scan failed with error code $errorCode"
        }
    }

    fun startScan() {
        _scannedDevices.value = emptyList()
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothAdapter.bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    fun stopScan() {
        Log.d(TAG, "Stopping scan")
        bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
    }

    fun sendFile(device: ScanResult, fileName: String, file: ByteArray) {
        this.fileName = fileName
        this.fileToSend = file
        bytesSent = 0
        _transferStatus.value = "Connecting..."
        bluetoothGatt = device.device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        transferInProgress = false
    }

    companion object {
        private const val TAG = "BleManager"
        val SERVICE_UUID: UUID = UUID.fromString("4ae29d01-499a-480a-8c41-a82192105125")
        val CLIENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("a00e530d-b48b-48c8-aadb-d062a1b91792")
        val SERVER_CHARACTERISTIC_UUID: UUID = UUID.fromString("0c656023-dee6-47c5-9afb-e601dfbdaa1d")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}