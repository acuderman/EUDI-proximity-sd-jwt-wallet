package com.eudi.wallet.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.eudi.wallet.models.BLEEngagementData
import com.eudi.wallet.models.VerificationRequest
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

@SuppressLint("MissingPermission")
class BLEWalletServer(private val context: Context) {
    
    companion object {
        private const val TAG = "BLEWalletServer"
        val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-5678-9abc-123456789abc")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("87654321-4321-8765-cba9-876543210def")
        const val DEVICE_NAME = "EUDI-Wallet"
        
        // Shared instance to maintain connection across activities
        var sharedInstance: BLEWalletServer? = null
    }
    
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    
    private val _serverState = MutableStateFlow<BLEServerState>(BLEServerState.Stopped)
    val serverState: StateFlow<BLEServerState> = _serverState
    
    private val _receivedRequest = MutableStateFlow<VerificationRequest?>(null)
    val receivedRequest: StateFlow<VerificationRequest?> = _receivedRequest
    
    private var connectedDevice: BluetoothDevice? = null
    private val gson = Gson()
    
    sealed class BLEServerState {
        object Stopped : BLEServerState()
        object Starting : BLEServerState()
        object Advertising : BLEServerState()
        object Connected : BLEServerState()
        data class Error(val message: String) : BLEServerState()
    }
    
    fun startServer(): BLEEngagementData? {
        try {
            bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            
            if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
                _serverState.value = BLEServerState.Error("Bluetooth not available or disabled")
                return null
            }
            
            _serverState.value = BLEServerState.Starting
            
            // Create GATT server
            bluetoothGattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
            
            // Create service
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or 
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or 
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            
            // Add Client Characteristic Configuration Descriptor for notifications
            val cccdDescriptor = BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            characteristic.addDescriptor(cccdDescriptor)
            
            service.addCharacteristic(characteristic)
            bluetoothGattServer?.addService(service)
            
            // Start advertising
            startAdvertising()
            
            return BLEEngagementData(
                deviceMac = bluetoothAdapter?.address ?: "00:00:00:00:00:00",
                serviceUuid = SERVICE_UUID.toString(),
                characteristicUuid = CHARACTERISTIC_UUID.toString()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE server", e)
            _serverState.value = BLEServerState.Error("Failed to start server: ${e.message}")
            return null
        }
    }
    
    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE advertising started successfully")
            _serverState.value = BLEServerState.Advertising
        }
        
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
            _serverState.value = BLEServerState.Error("Advertising failed: $errorCode")
        }
    }
    
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "Connection state change: device=${device.address}, status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Device connected: ${device.address}")
                    connectedDevice = device
                    _serverState.value = BLEServerState.Connected
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Device disconnected: ${device.address} (status=$status)")
                    if (connectedDevice?.address == device.address) {
                        connectedDevice = null
                        _serverState.value = BLEServerState.Advertising
                        Log.w(TAG, "Connection lost during active session")
                    }
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    Log.d(TAG, "Device connecting: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    Log.d(TAG, "Device disconnecting: ${device.address}")
                }
            }
        }
        
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "Write request: device=${device.address}, char=${characteristic.uuid}, offset=$offset, length=${value.size}")
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                try {
                    val receivedData = String(value, Charsets.UTF_8)
                    Log.d(TAG, "Received verification request: $receivedData")
                    
                    val request = gson.fromJson(receivedData, VerificationRequest::class.java)
                    _receivedRequest.value = request
                    
                    if (responseNeeded) {
                        bluetoothGattServer?.sendResponse(
                            device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null
                        )
                        Log.d(TAG, "Sent GATT_SUCCESS response")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process verification request", e)
                    if (responseNeeded) {
                        bluetoothGattServer?.sendResponse(
                            device, requestId, BluetoothGatt.GATT_FAILURE, offset, null
                        )
                        Log.d(TAG, "Sent GATT_FAILURE response")
                    }
                }
            } else {
                Log.w(TAG, "Write request for unknown characteristic: ${characteristic.uuid}")
            }
        }
        
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "Read request: device=${device.address}, char=${characteristic.uuid}, offset=$offset")
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                val response = "EUDI Wallet Ready".toByteArray(Charsets.UTF_8)
                bluetoothGattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response
                )
                Log.d(TAG, "Sent read response: EUDI Wallet Ready")
            } else {
                Log.w(TAG, "Read request for unknown characteristic: ${characteristic.uuid}")
            }
        }
        
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "Descriptor write request: device=${device.address}, descriptor=${descriptor.uuid}, value=${value.contentToString()}")
            
            if (descriptor.uuid == UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) {
                val isNotificationEnabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                Log.d(TAG, "Notifications ${if (isNotificationEnabled) "enabled" else "disabled"}")
                
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null
                    )
                    Log.d(TAG, "Sent descriptor write response")
                }
            } else {
                Log.w(TAG, "Write request for unknown descriptor: ${descriptor.uuid}")
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_FAILURE, offset, null
                    )
                }
            }
        }
    }
    
    fun sendResponse(vpToken: String) {
        Log.d(TAG, "Attempting to send VP Token response (${vpToken.length} chars)")
        Log.d(TAG, "Current connection state: ${_serverState.value}")
        
        val device = connectedDevice
        if (device == null) {
            Log.e(TAG, "No connected device to send response to - connection was lost during user interaction")
            return
        }
        
        Log.d(TAG, "Connected device: ${device.address}")
        
        val server = bluetoothGattServer
        if (server == null) {
            Log.e(TAG, "GATT server is null, cannot send response")
            return
        }
        
        val service = server.getService(SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "Service not found, cannot send response")
            return
        }
        
        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.e(TAG, "Characteristic not found, cannot send response")
            return
        }
        
        try {
            val tokenBytes = vpToken.toByteArray(Charsets.UTF_8)
            
            // BLE notifications have size limits, so chunk large responses
            val maxChunkSize = 500 // Safe size for BLE notifications
            
            if (tokenBytes.size <= maxChunkSize) {
                // Send as single notification
                characteristic.value = tokenBytes
                val success = server.notifyCharacteristicChanged(device, characteristic, false)
                
                if (success) {
                    Log.d(TAG, "VP Token notification sent successfully (${tokenBytes.size} bytes)")
                } else {
                    Log.e(TAG, "Failed to send VP Token notification")
                }
            } else {
                // Send in chunks
                Log.d(TAG, "VP Token too large (${tokenBytes.size} bytes), sending in chunks")
                sendChunkedResponse(device, server, characteristic, tokenBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while sending VP Token", e)
        }
    }
    
    private fun sendChunkedResponse(
        device: BluetoothDevice,
        server: BluetoothGattServer,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ) {
        val maxChunkSize = 500
        val totalChunks = (data.size + maxChunkSize - 1) / maxChunkSize
        
        Log.d(TAG, "Sending ${data.size} bytes in $totalChunks chunks")
        
        for (i in 0 until totalChunks) {
            val start = i * maxChunkSize
            val end = minOf(start + maxChunkSize, data.size)
            val chunk = data.sliceArray(start until end)
            
            // Add chunk header: [chunk_index][total_chunks][data...]
            val chunkWithHeader = ByteArray(chunk.size + 2)
            chunkWithHeader[0] = i.toByte()
            chunkWithHeader[1] = totalChunks.toByte()
            System.arraycopy(chunk, 0, chunkWithHeader, 2, chunk.size)
            
            characteristic.value = chunkWithHeader
            val success = server.notifyCharacteristicChanged(device, characteristic, false)
            
            Log.d(TAG, "Sent chunk $i/$totalChunks (${chunk.size} bytes): ${if (success) "success" else "failed"}")
            
            if (!success) {
                Log.e(TAG, "Failed to send chunk $i, aborting")
                return
            }
            
            // Small delay between chunks to avoid overwhelming the BLE stack
            Thread.sleep(50)
        }
        
        Log.d(TAG, "All chunks sent successfully")
    }
    
    fun stopServer() {
        advertiser?.stopAdvertising(advertiseCallback)
        bluetoothGattServer?.close()
        bluetoothGattServer = null
        connectedDevice = null
        _serverState.value = BLEServerState.Stopped
    }
}