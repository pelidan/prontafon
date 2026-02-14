package com.prontafon.presentation.screens.connection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.prontafon.domain.model.BtConnectionState
import com.prontafon.domain.model.displayText
import com.prontafon.presentation.components.*
import com.prontafon.presentation.theme.*
import com.prontafon.presentation.theme.InterFontFamily
import com.prontafon.util.permissions.PermissionState
import com.prontafon.util.permissions.rememberPermissionState

/**
 * Connection screen for device scanning and pairing.
 * 
 * Features:
 * - Device scanning with refresh button
 * - List of discovered devices (using DeviceListItem)
 * - Connection state display
 * - Automatic pairing via ECDH
 * - Navigate back when connected
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val bluetoothEnabled by viewModel.bluetoothEnabled.collectAsState()
    val error by viewModel.error.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Bluetooth permission state
    val bluetoothPermissions = remember {
        PermissionState.getRequiredBluetoothPermissions() + 
        PermissionState.getRequiredLocationPermissions()
    }
    
    val permissionState = rememberPermissionState(
        permissions = bluetoothPermissions,
        onPermissionsResult = { allGranted ->
            if (allGranted) {
                viewModel.startScan()
            }
        }
    )
    
    // Auto-navigate back when connection succeeds while on this screen
    // Track initial state to avoid auto-navigating if already connected when screen opens
    val initialConnectionState = remember { connectionState }
    
    val shouldNavigate = connectionState == BtConnectionState.CONNECTED && 
                         initialConnectionState != BtConnectionState.CONNECTED
    
    LaunchedEffect(shouldNavigate) {
        if (shouldNavigate) {
            onNavigateBack()
        }
    }
    
    // Show error snackbar
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    
    // Start scanning on first composition (only if permissions granted)
    LaunchedEffect(permissionState.allGranted) {
        if (permissionState.allGranted) {
            viewModel.startScan()
        } else if (bluetoothPermissions.isNotEmpty()) {
            // Request permissions if not granted
            permissionState.launchPermissionRequest()
        }
    }
    
    // If we're about to navigate away, show a loading spinner instead of the full content
    // This prevents the brief flash of the updated connection state before navigation
    if (shouldNavigate) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Primary)
        }
        return
    }
    
    Scaffold(
        topBar = {
            ProntafonTopBar(
                title = "Connect Device",
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(
                        onClick = { 
                            if (permissionState.allGranted) {
                                viewModel.startScan()
                            } else {
                                permissionState.launchPermissionRequest()
                            }
                        },
                        enabled = !isScanning && bluetoothEnabled
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ConnectionContent(
                scannedDevices = scannedDevices,
                connectionState = connectionState,
                connectedDevice = connectedDevice,
                isScanning = isScanning,
                bluetoothEnabled = bluetoothEnabled,
                hasBluetoothPermissions = permissionState.allGranted,
                onConnectDevice = viewModel::connectToDevice,
                onDisconnect = viewModel::disconnect,
                onStartScan = {
                    if (permissionState.allGranted) {
                        viewModel.startScan()
                    } else {
                        permissionState.launchPermissionRequest()
                    }
                },
                onRequestPermissions = { permissionState.launchPermissionRequest() }
            )
        }
    }
}

@Composable
private fun ConnectionContent(
    scannedDevices: List<com.prontafon.domain.model.BleDeviceInfo>,
    connectionState: BtConnectionState,
    connectedDevice: com.prontafon.domain.model.BleDeviceInfo?,
    isScanning: Boolean,
    bluetoothEnabled: Boolean,
    hasBluetoothPermissions: Boolean,
    onConnectDevice: (com.prontafon.domain.model.BleDeviceInfo) -> Unit,
    onDisconnect: () -> Unit,
    onStartScan: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Bluetooth Disabled Warning
        if (!bluetoothEnabled) {
            StatusCard(
                title = "Bluetooth Disabled",
                message = "Please enable Bluetooth to scan for devices",
                icon = Icons.Default.BluetoothDisabled,
                iconTint = Error
            )
            return
        }
        
        // Bluetooth Permission Required
        if (!hasBluetoothPermissions) {
            StatusCard(
                title = "Bluetooth Permission Required",
                message = "Grant Bluetooth permission to scan for nearby devices",
                icon = Icons.Default.Security,
                iconTint = Warning,
                action = {
                    Button(
                        onClick = onRequestPermissions,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = OnPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Grant Permission".uppercase())
                    }
                }
            )
            return
        }
        
        // Connection Status (show during active connection states and when connected)
        if (connectionState == BtConnectionState.CONNECTING || 
            connectionState == BtConnectionState.PAIRING ||
            connectionState == BtConnectionState.RECONNECTING ||
            connectionState == BtConnectionState.CONNECTED) {
            ConnectionStatusSection(
                connectionState = connectionState,
                connectedDevice = connectedDevice,
                onDisconnect = if (connectionState == BtConnectionState.CONNECTED) onDisconnect else null
            )
            Spacer(Modifier.height(16.dp))
        }
        
        // Scanning Indicator
        if (isScanning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp,
                    color = Primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Scanning for devices...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnBackgroundMuted
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        
        // Device List
        Text(
            text = "Available Devices",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        if (scannedDevices.isEmpty() && !isScanning) {
            EmptyDeviceList(onStartScan = onStartScan)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(scannedDevices, key = { it.address }) { device ->
                    // Determine if this specific device is currently being connected/paired
                    // Animation should show during CONNECTING, PAIRING, AWAITING_PAIRING, and RECONNECTING states
                    // but only for the device that is being connected
                    val isThisDeviceConnecting = connectedDevice?.address == device.address &&
                        (connectionState == BtConnectionState.CONNECTING ||
                         connectionState == BtConnectionState.PAIRING ||
                         connectionState == BtConnectionState.AWAITING_PAIRING ||
                         connectionState == BtConnectionState.RECONNECTING)
                    
                    DeviceListItem(
                        device = device,
                        onConnect = { onConnectDevice(device) },
                        isConnecting = isThisDeviceConnecting
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusSection(
    connectionState: BtConnectionState,
    connectedDevice: com.prontafon.domain.model.BleDeviceInfo?,
    onDisconnect: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Primary.copy(alpha = 0.1f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = connectionState.displayText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = InterFontFamily,
                        color = Primary
                    )
                    if (connectedDevice != null) {
                        Text(
                            text = connectedDevice.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnBackgroundMuted
                        )
                    }
                }
                
                // Show disconnect button when connected
                if (onDisconnect != null && connectionState == BtConnectionState.CONNECTED) {
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Disconnect",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Disconnect".uppercase())
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDeviceList(onStartScan: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        StatusCard(
            title = "No Devices Found",
            message = "Make sure your device is in pairing mode and nearby",
            icon = Icons.AutoMirrored.Filled.BluetoothSearching,
            iconTint = OnBackgroundSubtle,
            action = {
                Button(
                    onClick = onStartScan,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = OnPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Scan Again".uppercase())
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionScreenPreview() {
    ProntafonTheme {
        // Preview would need mock ViewModel
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Connection Screen Preview")
        }
    }
}
