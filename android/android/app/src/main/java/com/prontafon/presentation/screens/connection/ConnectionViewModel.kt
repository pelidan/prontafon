package com.prontafon.presentation.screens.connection

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prontafon.domain.model.BleDeviceInfo
import com.prontafon.domain.model.BtConnectionState
import com.prontafon.service.ble.BleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Connection screen.
 * Manages BLE device scanning, connection, and pairing.
 * 
 * Features:
 * - Scan for BLE devices
 * - Connect to selected device
 * - Handle pairing flow with ECDH
 * - Display scanned devices sorted by relevance
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val bleManager: BleManager
) : ViewModel() {
    
    companion object {
        private const val TAG = "ConnectionViewModel"
        private const val PAIRING_TIMEOUT_MS = 30_000L // 30 seconds
    }
    
    // ==================== UI State ====================
    
    /**
     * List of scanned BLE devices
     */
    val scannedDevices: StateFlow<List<BleDeviceInfo>> = bleManager.scannedDevices
    
    /**
     * Current BLE connection state
     */
    val connectionState: StateFlow<BtConnectionState> = bleManager.connectionState
    
    /**
     * Currently connected device (if any)
     */
    val connectedDevice: StateFlow<BleDeviceInfo?> = bleManager.connectedDevice
    
    /**
     * Whether scanning is in progress
     */
    val isScanning: StateFlow<Boolean> = bleManager.isScanning
    
    /**
     * Whether Bluetooth is enabled
     */
    private val _bluetoothEnabled = MutableStateFlow(true)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()
    
    /**
     * Error message for display
     */
    val error: StateFlow<String?> = bleManager.error
    
    // ==================== Private State ====================
    
    private var pendingDevice: BleDeviceInfo? = null
    private var pairingTimeoutJob: Job? = null
    
    // ==================== Initialization ====================
    
    init {
        // Check if Bluetooth is enabled
        updateBluetoothState()
        
        // Monitor connection state for timeout management
        monitorConnectionState()
    }
    
    // ==================== Actions ====================
    
    /**
     * Start scanning for BLE devices
     */
    fun startScan() {
        updateBluetoothState()
        
        if (!bleManager.isBluetoothEnabled()) {
            _bluetoothEnabled.value = false
            return
        }
        
        _bluetoothEnabled.value = true
        bleManager.startScan()
    }
    
    /**
     * Stop scanning for BLE devices
     */
    fun stopScan() {
        bleManager.stopScan()
    }
    
    /**
     * Connect to a BLE device with timeout
     */
    fun connectToDevice(device: BleDeviceInfo) {
        viewModelScope.launch {
            stopScan()
            pendingDevice = device
            
            // Start pairing timeout
            startPairingTimeout()
            
            bleManager.connect(device)
        }
    }
    
    /**
     * Cancel pairing process
     */
    fun cancelPairing() {
        cancelPairingTimeout()
        bleManager.disconnect()
    }
    
    /**
     * Disconnect from current device
     */
    fun disconnect() {
        cancelPairingTimeout()
        bleManager.disconnect()
    }
    
    /**
     * Clear current error
     */
    fun clearError() {
        // BleManager doesn't expose error clearing, so we'll just dismiss locally
        // The error will be cleared on next successful operation
    }
    
    // ==================== Private Methods ====================
    
    private fun updateBluetoothState() {
        _bluetoothEnabled.value = bleManager.isBluetoothEnabled()
    }
    
    /**
     * Monitor connection state changes to manage timeout
     */
    private fun monitorConnectionState() {
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    BtConnectionState.CONNECTED -> {
                        // Connection successful - cancel timeout
                        cancelPairingTimeout()
                        Log.d(TAG, "Connection successful, timeout cancelled")
                    }
                    BtConnectionState.FAILED, BtConnectionState.DISCONNECTED -> {
                        // Connection failed or disconnected - cancel timeout
                        cancelPairingTimeout()
                        Log.d(TAG, "Connection failed/disconnected, timeout cancelled")
                    }
                    BtConnectionState.CONNECTING, BtConnectionState.PAIRING, 
                    BtConnectionState.AWAITING_PAIRING -> {
                        // These states should have timeout running (started in connectToDevice)
                        Log.d(TAG, "Connection state: $state (timeout should be active)")
                    }
                    else -> {
                        // Other states - ensure timeout is cancelled
                        cancelPairingTimeout()
                    }
                }
            }
        }
    }
    
    /**
     * Start pairing timeout timer
     */
    private fun startPairingTimeout() {
        // Cancel any existing timeout
        pairingTimeoutJob?.cancel()
        
        Log.d(TAG, "Starting pairing timeout (${PAIRING_TIMEOUT_MS}ms)")
        
        pairingTimeoutJob = viewModelScope.launch {
            delay(PAIRING_TIMEOUT_MS)
            
            // Check if still in connecting/pairing state
            val currentState = connectionState.value
            if (currentState == BtConnectionState.CONNECTING || 
                currentState == BtConnectionState.PAIRING ||
                currentState == BtConnectionState.AWAITING_PAIRING) {
                Log.w(TAG, "Pairing timeout reached in state: $currentState")
                handlePairingTimeout()
            }
        }
    }
    
    /**
     * Cancel pairing timeout timer
     */
    private fun cancelPairingTimeout() {
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
    }
    
    /**
     * Handle pairing timeout
     */
    private fun handlePairingTimeout() {
        Log.e(TAG, "Pairing timeout - disconnecting")
        
        // Disconnect and show error
        bleManager.disconnect()
        
        // Note: We can't directly set error in BleManager from here
        // The BleManager should handle setting its own error state
        // For now, the timeout will trigger a disconnect which should 
        // update the connection state to FAILED or DISCONNECTED
    }
    
    // ==================== Lifecycle ====================
    
    override fun onCleared() {
        super.onCleared()
        // Stop scanning when ViewModel is cleared
        stopScan()
        // Cancel any pending timeout
        cancelPairingTimeout()
    }
}
