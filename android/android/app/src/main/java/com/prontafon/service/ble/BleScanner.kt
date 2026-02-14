package com.prontafon.service.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.prontafon.domain.model.BleDeviceInfo
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

/**
 * BLE scanner for discovering Prontafon devices.
 * 
 * Features:
 * - Filter by Prontafon service UUID
 * - Emit scan results via StateFlow
 * - Handle scan permissions
 * - Auto-stop after timeout
 * - Handle Bluetooth adapter state
 */
@Singleton
class BleScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BleScanner"
    }
    
    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }
    
    private var bluetoothScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var timeoutJob: Job? = null
    
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    
    // State flows
    private val _scannedDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    val scannedDevices: StateFlow<List<BleDeviceInfo>> = _scannedDevices.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    /**
     * Check if Bluetooth is supported and enabled.
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    /**
     * Start scanning for Prontafon BLE devices.
     * Automatically stops after timeout.
     * 
     * @param timeoutMs Scan timeout in milliseconds (default: 15 seconds)
     * @return true if scan started successfully
     */
    @SuppressLint("MissingPermission")
    fun startScan(timeoutMs: Long = BleConstants.SCAN_TIMEOUT.inWholeMilliseconds): Boolean {
        if (_isScanning.value) {
            Log.w(TAG, "Scan already in progress")
            return false
        }
        
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            _error.value = "Bluetooth not available or not enabled"
            return false
        }
        
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner not available")
            _error.value = "BLE scanner not available"
            return false
        }
        
        Log.d(TAG, "Starting BLE scan...")
        
        // Clear previous results
        _scannedDevices.value = emptyList()
        _error.value = null
        bluetoothScanner = scanner
        
        // Scan settings for balanced power/latency
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0) // Report immediately
            .build()
        
        // Filter for Prontafon service UUID
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID)))
            .build()
        
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }
            
            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { handleScanResult(it) }
            }
            
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error code: $errorCode")
                _error.value = "Scan failed: error $errorCode"
                _isScanning.value = false
            }
        }
        
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            _isScanning.value = true
            
            // Schedule auto-stop
            timeoutJob = scope.launch {
                delay(timeoutMs)
                stopScan()
            }
            
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting scan", e)
            _error.value = "Permission denied for BLE scan"
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan", e)
            _error.value = "Failed to start scan: ${e.message}"
            return false
        }
    }
    
    /**
     * Stop the current BLE scan.
     */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) {
            return
        }
        
        timeoutJob?.cancel()
        timeoutJob = null
        
        scanCallback?.let { callback ->
            try {
                bluetoothScanner?.stopScan(callback)
                Log.d(TAG, "Scan stopped")
            } catch (e: SecurityException) {
                Log.w(TAG, "Security exception stopping scan", e)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping scan", e)
            }
        }
        
        scanCallback = null
        _isScanning.value = false
    }
    
    /**
     * Clear scanned devices list.
     */
    fun clearDevices() {
        _scannedDevices.value = emptyList()
    }
    
    /**
     * Handle a scan result.
     */
    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val scanRecord = result.scanRecord
        
        // Check if Prontafon service is advertised
        val serviceUuids = scanRecord?.serviceUuids ?: emptyList()
        val hasProntafonService = serviceUuids.any { 
            it.uuid.toString().equals(BleConstants.SERVICE_UUID, ignoreCase = true)
        }
        
        val name = scanRecord?.deviceName 
            ?: device.name 
            ?: ""
        
        val deviceInfo = BleDeviceInfo(
            name = name,
            address = device.address,
            rssi = result.rssi,
            hasProntafonService = hasProntafonService,
            device = device
        )
        
        // Only add Prontafon devices
        if (!deviceInfo.isProntafon) {
            return
        }
        
        val currentList = _scannedDevices.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.address == deviceInfo.address }
        
        if (existingIndex >= 0) {
            // Update existing device (RSSI may have changed)
            currentList[existingIndex] = deviceInfo
        } else {
            // Add new device
            currentList.add(deviceInfo)
            Log.d(TAG, "Found device: ${deviceInfo.displayName} (${deviceInfo.address})")
        }
        
        _scannedDevices.value = currentList
    }
}
