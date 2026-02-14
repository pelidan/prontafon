package com.prontafon.service.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles BLE characteristic read/write operations.
 * 
 * Features:
 * - Read/write to TX/RX characteristics
 * - Enable notifications
 * - Handle large data chunking (if > MTU)
 * - Emit received data via Flow
 * - Handle write queue
 */
@Singleton
class BleCharacteristicHandler @Inject constructor() {
    companion object {
        private const val TAG = "BleCharHandler"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val WRITE_DELAY_MS = 10L
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_BASE_MS = 100L
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    
    // Received data flow
    private val _receivedData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val receivedData: SharedFlow<ByteArray> = _receivedData.asSharedFlow()
    
    // Status updates flow
    private val _statusUpdates = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val statusUpdates: SharedFlow<ByteArray> = _statusUpdates.asSharedFlow()
    
    // Write queue
    private val writeQueue = mutableListOf<WriteRequest>()
    private val writeMutex = Mutex()
    private var isWriting = false
    
    /**
     * Enable notifications for Response TX characteristic.
     * 
     * @param gatt GATT connection
     * @return true if notification setup initiated
     */
    @SuppressLint("MissingPermission")
    suspend fun enableResponseNotifications(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(UUID.fromString(BleConstants.SERVICE_UUID))
        if (service == null) {
            Log.e(TAG, "Prontafon service not found")
            return false
        }
        
        val characteristic = service.getCharacteristic(UUID.fromString(BleConstants.RESPONSE_TX_UUID))
        if (characteristic == null) {
            Log.e(TAG, "Response TX characteristic not found (UUID: ${BleConstants.RESPONSE_TX_UUID})")
            return false
        }
        
        // Enable local notifications
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.e(TAG, "Failed to enable characteristic notification for Response TX")
            return false
        }
        
        // Write to CCCD to enable notifications on the remote device
        val descriptor = characteristic.getDescriptor(UUID.fromString(CCCD_UUID))
        if (descriptor == null) {
            Log.e(TAG, "CCCD descriptor not found for Response TX")
            return false
        }
        
        Log.d(TAG, "Attempting to enable Response TX notifications...")
        
        // Retry descriptor write with exponential backoff
        return retryDescriptorWrite(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, "Response TX")
    }
    
    /**
     * Enable notifications for Status characteristic.
     * 
     * @param gatt GATT connection
     * @return true if notification setup initiated
     */
    @SuppressLint("MissingPermission")
    suspend fun enableStatusNotifications(gatt: BluetoothGatt): Boolean {
        // Small delay to avoid GATT queue issues
        delay(100)
        
        val service = gatt.getService(UUID.fromString(BleConstants.SERVICE_UUID))
        if (service == null) {
            Log.e(TAG, "Prontafon service not found")
            return false
        }
        
        val characteristic = service.getCharacteristic(UUID.fromString(BleConstants.STATUS_UUID))
        if (characteristic == null) {
            Log.e(TAG, "Status characteristic not found (UUID: ${BleConstants.STATUS_UUID})")
            return false
        }
        
        // Enable local notifications
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.e(TAG, "Failed to enable status characteristic notification")
            return false
        }
        
        // Write to CCCD
        val descriptor = characteristic.getDescriptor(UUID.fromString(CCCD_UUID))
        if (descriptor == null) {
            Log.e(TAG, "Status CCCD descriptor not found")
            return false
        }
        
        Log.d(TAG, "Attempting to enable Status notifications...")
        
        // Retry descriptor write with exponential backoff
        return retryDescriptorWrite(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, "Status")
    }
    
    /**
     * Write data to Command RX characteristic with chunking.
     * 
     * @param gatt GATT connection
     * @param data Data to write
     * @param mtu Current MTU size
     * @return true if write initiated successfully
     */
    @SuppressLint("MissingPermission")
    suspend fun writeCommand(gatt: BluetoothGatt, data: ByteArray, mtu: Int): Boolean {
        val service = gatt.getService(UUID.fromString(BleConstants.SERVICE_UUID))
        if (service == null) {
            Log.e(TAG, "Prontafon service not found for write operation")
            return false
        }
        
        val characteristic = service.getCharacteristic(UUID.fromString(BleConstants.COMMAND_RX_UUID))
        if (characteristic == null) {
            Log.e(TAG, "Command RX characteristic not found (UUID: ${BleConstants.COMMAND_RX_UUID})")
            return false
        }
        
        // Chunk data using PacketAssembler
        val packets = PacketAssembler.chunkMessage(data, mtu)
        Log.d(TAG, "Writing ${packets.size} packet(s) (total ${data.size} bytes, MTU $mtu)")
        
        // Write each packet with delay to avoid overwhelming BLE stack
        for ((index, packet) in packets.withIndex()) {
            val success = retryCharacteristicWrite(gatt, characteristic, packet, index + 1, packets.size)
            if (!success) {
                Log.e(TAG, "Failed to write packet ${index + 1}/${packets.size} after retries")
                return false
            }
            
            // Small delay between packets
            if (packets.size > 1 && index < packets.size - 1) {
                delay(WRITE_DELAY_MS)
            }
        }
        
        Log.d(TAG, "Successfully wrote all ${packets.size} packet(s)")
        return true
    }
    
    /**
     * Write to a characteristic.
     */
    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        characteristic.value = data
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        
        return try {
            gatt.writeCharacteristic(characteristic)
        } catch (e: Exception) {
            Log.e(TAG, "Exception writing characteristic: ${e.message}", e)
            false
        }
    }
    
    /**
     * Retry characteristic write with exponential backoff.
     * 
     * @param gatt GATT connection
     * @param characteristic Characteristic to write to
     * @param data Data to write
     * @param packetNum Current packet number (for logging)
     * @param totalPackets Total number of packets (for logging)
     * @return true if write succeeded
     */
    @SuppressLint("MissingPermission")
    private suspend fun retryCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        packetNum: Int,
        totalPackets: Int
    ): Boolean {
        val charUuid = characteristic.uuid.toString()
        
        for (attempt in 1..MAX_RETRIES) {
            Log.d(TAG, "Writing packet $packetNum/$totalPackets to $charUuid (attempt $attempt/$MAX_RETRIES, ${data.size} bytes)")
            
            val success = writeCharacteristic(gatt, characteristic, data)
            
            if (success) {
                if (attempt > 1) {
                    Log.d(TAG, "Write succeeded on retry attempt $attempt")
                }
                return true
            }
            
            // Don't delay after the last failed attempt
            if (attempt < MAX_RETRIES) {
                val delayMs = RETRY_DELAY_BASE_MS * (1 shl (attempt - 1)) // 100ms, 200ms, 400ms
                Log.w(TAG, "Write failed (attempt $attempt/$MAX_RETRIES), retrying in ${delayMs}ms...")
                delay(delayMs)
            } else {
                Log.e(TAG, "Write failed after $MAX_RETRIES attempts: characteristic=$charUuid, packet=$packetNum/$totalPackets, dataSize=${data.size}")
            }
        }
        
        return false
    }
    
    /**
     * Retry descriptor write with exponential backoff.
     * 
     * @param gatt GATT connection
     * @param descriptor Descriptor to write to
     * @param value Value to write
     * @param name Friendly name for logging
     * @return true if write succeeded
     */
    @SuppressLint("MissingPermission")
    private suspend fun retryDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
        name: String
    ): Boolean {
        val descUuid = descriptor.uuid.toString()
        val charUuid = descriptor.characteristic?.uuid.toString()
        
        for (attempt in 1..MAX_RETRIES) {
            Log.d(TAG, "Writing $name descriptor (attempt $attempt/$MAX_RETRIES): descriptor=$descUuid, characteristic=$charUuid")
            
            descriptor.value = value
            val success = try {
                gatt.writeDescriptor(descriptor)
            } catch (e: Exception) {
                Log.e(TAG, "Exception writing descriptor: ${e.message}", e)
                false
            }
            
            if (success) {
                Log.d(TAG, "Enabled $name notifications" + if (attempt > 1) " (succeeded on attempt $attempt)" else "")
                return true
            }
            
            // Don't delay after the last failed attempt
            if (attempt < MAX_RETRIES) {
                val delayMs = RETRY_DELAY_BASE_MS * (1 shl (attempt - 1)) // 100ms, 200ms, 400ms
                Log.w(TAG, "Descriptor write failed (attempt $attempt/$MAX_RETRIES), retrying in ${delayMs}ms...")
                delay(delayMs)
            } else {
                Log.e(TAG, "Failed to write $name CCCD descriptor after $MAX_RETRIES attempts: descriptor=$descUuid, characteristic=$charUuid")
            }
        }
        
        return false
    }
    
    /**
     * Handle characteristic change (notification/indication received).
     */
    fun handleCharacteristicChange(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val uuid = characteristic.uuid.toString().lowercase()
        Log.d(TAG, "Notification received from ${uuid}: ${value.size} bytes")
        
        when (uuid) {
            BleConstants.RESPONSE_TX_UUID.lowercase() -> {
                Log.d(TAG, "Response TX notification: ${value.size} bytes, flags=${value.getOrNull(0)?.toInt()?.and(0xFF)?.toString(16)}")
                // Emit response data
                scope.launch {
                    _receivedData.emit(value)
                }
            }
            BleConstants.STATUS_UUID.lowercase() -> {
                Log.d(TAG, "Status notification: ${value.size} bytes, status=${value.getOrNull(0)?.toInt()?.and(0xFF)?.toString(16)}")
                // Emit status update
                scope.launch {
                    _statusUpdates.emit(value)
                }
            }
            else -> {
                Log.d(TAG, "Received data from unknown characteristic: ${characteristic.uuid}")
            }
        }
    }
    
    /**
     * Clear all queued operations.
     */
    fun clearQueue() {
        scope.launch {
            writeMutex.withLock {
                writeQueue.clear()
                isWriting = false
            }
        }
    }
    
    private data class WriteRequest(
        val characteristic: BluetoothGattCharacteristic,
        val data: ByteArray
    )
}
