package com.prontafon.service.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.prontafon.domain.model.BtConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * BLE connection manager with MTU negotiation and reconnection.
 * 
 * Features:
 * - Connect/disconnect to BLE device
 * - MTU negotiation (target 512 bytes)
 * - Discover services and characteristics
 * - Monitor connection state
 * - Handle connection errors
 * - Auto-reconnect with exponential backoff
 */
@Singleton
class BleConnection @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BleConnection"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val SERVICE_DISCOVERY_DELAY_MS = 500L
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    
    // GATT connection
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    
    // State flows
    private val _connectionState = MutableStateFlow(BtConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BtConnectionState> = _connectionState.asStateFlow()
    
    private val _mtu = MutableStateFlow(BleConstants.DEFAULT_MTU)
    val mtu: StateFlow<Int> = _mtu.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Reconnection
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    
    // Flag to distinguish user-initiated disconnect from connection loss
    private var userInitiatedDisconnect = false
    
    // Callbacks
    var onServicesDiscovered: ((BluetoothGatt) -> Unit)? = null
    var onCharacteristicChanged: ((BluetoothGattCharacteristic, ByteArray) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    
    /**
     * Connect to a BLE device.
     * 
     * @param device Device to connect to
     * @return true if connection initiated successfully
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        if (_connectionState.value == BtConnectionState.CONNECTING ||
            _connectionState.value == BtConnectionState.CONNECTED) {
            Log.w(TAG, "Already connecting or connected")
            return false
        }
        
        Log.d(TAG, "Connecting to ${device.address}...")
        
        setState(BtConnectionState.CONNECTING)
        _error.value = null
        reconnectAttempts = 0
        userInitiatedDisconnect = false  // Reset flag for new connection
        connectedDevice = device
        
        try {
            // Connect with auto-connect disabled for faster initial connection
            bluetoothGatt = device.connectGatt(
                context,
                false, // autoConnect
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
            
            return bluetoothGatt != null
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception connecting", e)
            _error.value = "Permission denied for BLE connection"
            setState(BtConnectionState.FAILED)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting", e)
            _error.value = "Connection failed: ${e.message}"
            setState(BtConnectionState.FAILED)
            return false
        }
    }
    
    /**
     * Disconnect from the current device.
     * This is a user-initiated disconnect and will NOT auto-reconnect.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        Log.d(TAG, "User-initiated disconnect...")
        
        // Set flag to prevent auto-reconnect
        userInitiatedDisconnect = true
        
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        
        try {
            bluetoothGatt?.disconnect()
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception disconnecting", e)
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting", e)
        }
    }
    
    /**
     * Get the current GATT connection.
     */
    fun getGatt(): BluetoothGatt? = bluetoothGatt
    
    /**
     * Set connection state to AWAITING_PAIRING.
     * Called when the desktop device requests PIN entry.
     */
    fun setAwaitingPairing() {
        // Allow transition from CONNECTED or already AWAITING_PAIRING (idempotent)
        val currentState = _connectionState.value
        if (currentState == BtConnectionState.CONNECTED || 
            currentState == BtConnectionState.AWAITING_PAIRING) {
            if (currentState != BtConnectionState.AWAITING_PAIRING) {
                Log.d(TAG, "Transitioning to AWAITING_PAIRING for PIN entry")
                setState(BtConnectionState.AWAITING_PAIRING)
            } else {
                Log.d(TAG, "Already in AWAITING_PAIRING state (no-op)")
            }
        } else {
            Log.w(TAG, "Cannot transition to AWAITING_PAIRING from state: $currentState")
        }
    }
    
    /**
     * Complete pairing and transition back to CONNECTED state.
     * Called when PAIR_ACK with success is received.
     */
    fun completePairing() {
        val currentState = _connectionState.value
        if (currentState == BtConnectionState.PAIRING || 
            currentState == BtConnectionState.AWAITING_PAIRING) {
            Log.d(TAG, "Pairing complete, transitioning to CONNECTED")
            setState(BtConnectionState.CONNECTED)
        } else {
            Log.w(TAG, "completePairing called from unexpected state: $currentState")
        }
    }
    
    /**
     * Fail pairing and transition to FAILED state.
     * Called when PAIR_ACK with failure is received.
     */
    fun failPairing() {
        val currentState = _connectionState.value
        if (currentState == BtConnectionState.PAIRING || 
            currentState == BtConnectionState.AWAITING_PAIRING) {
            Log.d(TAG, "Pairing failed, transitioning to FAILED")
            setState(BtConnectionState.FAILED)
        } else {
            Log.w(TAG, "failPairing called from unexpected state: $currentState")
        }
    }
    
    /**
     * Clean up resources.
     */
    @SuppressLint("MissingPermission")
    private fun cleanup() {
        bluetoothGatt?.let { gatt ->
            try {
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing GATT", e)
            }
        }
        
        bluetoothGatt = null
        connectedDevice = null
        setState(BtConnectionState.DISCONNECTED)
    }
    
    /**
     * Handle disconnection with reconnection logic.
     * Only auto-reconnects on connection loss, NOT on user-initiated disconnect.
     */
    private fun handleDisconnection() {
        val device = connectedDevice
        val wasConnected = bluetoothGatt != null
        val wasUserInitiated = userInitiatedDisconnect
        
        // Reset the flag for next connection
        userInitiatedDisconnect = false
        
        onDisconnected?.invoke()
        
        // Only auto-reconnect if:
        // 1. Was connected and has device info
        // 2. NOT a user-initiated disconnect
        // 3. Haven't exceeded max reconnect attempts
        if (wasConnected && device != null && 
            !wasUserInitiated && 
            reconnectAttempts < BleConstants.MAX_RECONNECT_ATTEMPTS) {
            Log.d(TAG, "Connection lost, attempting auto-reconnect...")
            setState(BtConnectionState.RECONNECTING)
            scheduleReconnect(device)
        } else {
            if (wasUserInitiated) {
                Log.d(TAG, "User-initiated disconnect, not auto-reconnecting")
            }
            cleanup()
        }
    }
    
    /**
     * Schedule reconnection with exponential backoff.
     */
    private fun scheduleReconnect(device: BluetoothDevice) {
        reconnectJob?.cancel()
        
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s
        val delayMs = (1000L shl reconnectAttempts).coerceAtMost(16_000L)
        Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt ${reconnectAttempts + 1})")
        
        reconnectJob = scope.launch {
            delay(delayMs)
            reconnectAttempts++
            connect(device)
        }
    }
    
    private fun setState(state: BtConnectionState) {
        if (_connectionState.value != state) {
            Log.d(TAG, "State: ${_connectionState.value} -> $state")
            _connectionState.value = state
        }
    }
    
    // GATT callback
    private val gattCallback = object : BluetoothGattCallback() {
        
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: status=$status, newState=$newState")
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "Connected, requesting MTU...")
                        // Reset reconnect attempts on successful connection
                        reconnectAttempts = 0
                        // Request higher MTU before discovering services
                        gatt.requestMtu(BleConstants.TARGET_MTU)
                    } else {
                        Log.e(TAG, "Connection failed with status: $status")
                        _error.value = "Connection failed with status: $status"
                        handleDisconnection()
                    }
                }
                
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected")
                    handleDisconnection()
                }
            }
        }
        
        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _mtu.value = mtu
                Log.d(TAG, "MTU negotiated: $mtu")
            } else {
                _mtu.value = BleConstants.DEFAULT_MTU
                Log.w(TAG, "MTU negotiation failed, using default: ${BleConstants.DEFAULT_MTU}")
            }
            
            // Now discover services
            Log.d(TAG, "Discovering services...")
            gatt.discoverServices()
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: status=$status (${getGattErrorDescription(status)})")
                _error.value = "Service discovery failed: ${getGattErrorDescription(status)}"
                setState(BtConnectionState.FAILED)
                return
            }
            
            Log.d(TAG, "Services discovered, waiting ${SERVICE_DISCOVERY_DELAY_MS}ms for services to stabilize...")
            
            // Add delay to let services fully initialize before attempting writes
            scope.launch {
                delay(SERVICE_DISCOVERY_DELAY_MS)
                
                // Find Prontafon service
                val service = gatt.getService(UUID.fromString(BleConstants.SERVICE_UUID))
                if (service == null) {
                    Log.e(TAG, "Prontafon service not found")
                    _error.value = "Prontafon service not found"
                    setState(BtConnectionState.FAILED)
                    return@launch
                }
                
                Log.d(TAG, "Found Prontafon service with ${service.characteristics.size} characteristics")
                // Set to PAIRING state - will transition to CONNECTED when PAIR_ACK is received
                setState(BtConnectionState.PAIRING)
                
                // Notify callback
                onServicesDiscovered?.invoke(gatt)
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onCharacteristicChanged?.invoke(characteristic, value)
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val charUuid = characteristic.uuid.toString()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Characteristic write failed: uuid=$charUuid, status=$status (${getGattErrorDescription(status)}), MTU=${_mtu.value}")
            } else {
                Log.d(TAG, "Characteristic write success: uuid=$charUuid")
            }
        }
        
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val descUuid = descriptor.uuid.toString()
            val charUuid = descriptor.characteristic?.uuid.toString()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor written successfully: descriptor=$descUuid, characteristic=$charUuid")
            } else {
                Log.w(TAG, "Descriptor write failed: descriptor=$descUuid, characteristic=$charUuid, status=$status (${getGattErrorDescription(status)})")
            }
        }
    }
    
    /**
     * Get human-readable description for GATT error codes.
     */
    private fun getGattErrorDescription(errorCode: Int): String {
        return when (errorCode) {
            0 -> "SUCCESS"
            1 -> "GATT_FAILURE (generic failure)"
            2 -> "GATT_READ_NOT_PERMITTED"
            3 -> "GATT_WRITE_NOT_PERMITTED"
            5 -> "GATT_INSUFFICIENT_AUTHENTICATION"
            6 -> "GATT_REQUEST_NOT_SUPPORTED"
            7 -> "GATT_INVALID_OFFSET"
            13 -> "GATT_INVALID_ATTRIBUTE_LENGTH"
            15 -> "GATT_INSUFFICIENT_ENCRYPTION"
            133 -> "GATT_ERROR (connection lost)"
            257 -> "GATT_ERROR (user cancel/local error)"
            else -> "Unknown error: $errorCode"
        }
    }
}
