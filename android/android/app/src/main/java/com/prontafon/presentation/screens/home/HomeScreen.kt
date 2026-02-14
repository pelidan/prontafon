package com.prontafon.presentation.screens.home

import android.Manifest
import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.prontafon.domain.model.BtConnectionState
import com.prontafon.domain.model.displayText
import com.prontafon.presentation.components.*
import com.prontafon.presentation.theme.*
import androidx.compose.ui.res.painterResource
import com.prontafon.R

/**
 * Main screen with speech recognition controls.
 * 
 * Features:
 * - Connection status indicator at top
 * - Large microphone button (start/stop listening)
 * - Current recognized text display
 * - Sound level visualizer
 * - Error messages
 * - Navigate to Connection screen if not connected
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToConnection: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    
    // Keep screen on while this screen is displayed
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    
    // Permission launcher for microphone
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, try to start listening again
            viewModel.startListening()
        }
    }
    
    // Handle one-time events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.NavigateTo -> {
                    when (event.route) {
                        "connection" -> onNavigateToConnection()
                        "settings" -> onNavigateToSettings()
                    }
                }
                is HomeEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                HomeEvent.RequestMicrophonePermission -> {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
    
    Scaffold(
        topBar = {
            ProntafonTopBar(
                title = "Prontafon",
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
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
            if (!uiState.isInitialized) {
                // Loading state
                LoadingScreen(message = "Initializing speech recognition...")
            } else {
                HomeContent(
                    uiState = uiState,
                    onToggleListening = viewModel::toggleListening,
                    onNavigateToConnection = onNavigateToConnection,
                    onClearError = viewModel::clearError,
                    onDisconnect = viewModel::disconnect,
                    onCancelAutoReconnect = viewModel::cancelAutoReconnect
                )
            }
        }
    }
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    onToggleListening: () -> Unit,
    onNavigateToConnection: () -> Unit,
    onClearError: () -> Unit,
    onDisconnect: () -> Unit,
    onCancelAutoReconnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Connection Status Card
        ConnectionStatusCard(
            connectionState = uiState.connectionState,
            connectedDevice = uiState.connectedDevice,
            isAutoReconnecting = uiState.isAutoReconnecting,
            onConnect = onNavigateToConnection,
            onDisconnect = onDisconnect,
            onCancelAutoReconnect = onCancelAutoReconnect
        )
        
        Spacer(Modifier.height(24.dp))
        
        // Error Message
        if (uiState.errorMessage != null) {
            ErrorMessage(
                message = uiState.errorMessage,
                onRetry = onClearError
            )
            Spacer(Modifier.height(16.dp))
        }
        
        // Microphone Button with Sound Level Visualizer
        MicrophoneButton(
            isListening = uiState.isListening,
            soundLevel = uiState.soundLevel,
            isConnected = uiState.connectionState == BtConnectionState.CONNECTED,
            onClick = onToggleListening
        )
        
        Spacer(Modifier.height(32.dp))
        
        // Current Text Display
        if (uiState.showRecognizedText && (uiState.isListening || uiState.currentText.isNotEmpty())) {
            CurrentTextCard(
                text = uiState.currentText,
                isListening = uiState.isListening
            )
            Spacer(Modifier.height(16.dp))
        }
        
        // Instructions
        if (!uiState.isListening && uiState.currentText.isEmpty()) {
            InstructionsCard(isConnected = uiState.connectionState == BtConnectionState.CONNECTED)
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    connectionState: BtConnectionState,
    connectedDevice: com.prontafon.domain.model.BleDeviceInfo?,
    isAutoReconnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCancelAutoReconnect: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Icon
            val icon = when {
                isAutoReconnecting -> Icons.AutoMirrored.Filled.BluetoothSearching
                connectionState == BtConnectionState.CONNECTED -> Icons.Default.BluetoothConnected
                connectionState == BtConnectionState.CONNECTING || connectionState == BtConnectionState.RECONNECTING -> Icons.AutoMirrored.Filled.BluetoothSearching
                else -> Icons.Default.Bluetooth
            }
            
            val iconColor = when {
                isAutoReconnecting -> Primary
                connectionState == BtConnectionState.CONNECTED -> Primary
                connectionState == BtConnectionState.CONNECTING || connectionState == BtConnectionState.RECONNECTING -> OnBackgroundMuted
                else -> OnBackgroundSubtle
            }
            
            Icon(
                imageVector = icon,
                contentDescription = "Connection Status",
                tint = iconColor,
                modifier = Modifier.size(32.dp)
            )
            
            Spacer(Modifier.width(16.dp))
            
            // Status Text
            Column(modifier = Modifier.weight(1f)) {
                if (isAutoReconnecting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Reconnecting...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            fontFamily = InterFontFamily,
                            color = Color.White
                        )
                    }
                    if (connectedDevice != null) {
                        Text(
                            text = "to ${connectedDevice.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnBackgroundSubtle
                        )
                    }
                } else {
                    Text(
                        text = connectionState.displayText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        fontFamily = InterFontFamily,
                        color = Color.White
                    )
                    if (connectedDevice != null) {
                        Text(
                            text = connectedDevice.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = OnBackgroundSubtle
                        )
                    }
                }
            }
            
            // Connect/Disconnect/Cancel Button
            if (isAutoReconnecting) {
                Button(
                    onClick = onCancelAutoReconnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("CANCEL")
                }
            } else if (connectionState == BtConnectionState.DISCONNECTED || connectionState == BtConnectionState.FAILED) {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = OnPrimary
                    )
                ) {
                    Text("CONNECT")
                }
            } else if (connectionState == BtConnectionState.CONNECTED) {
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Disconnect")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("DISCONNECT")
                }
            }
        }
    }
}

@Composable
private fun MicrophoneButton(
    isListening: Boolean,
    soundLevel: Float,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isListening) 1f + (soundLevel * 0.2f) else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "micScale"
    )
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(160.dp)
    ) {
        // Main button - just the image
        val interactionSource = remember { MutableInteractionSource() }
        Image(
            painter = painterResource(
                id = if (isListening) R.drawable.stop else R.drawable.start
            ),
            contentDescription = if (isListening) "Stop listening" else "Start listening",
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
        )
    }
    
    Spacer(Modifier.height(8.dp))
    
    // Status text
    Text(
        text = when {
            !isConnected -> "Connect to device first"
            isListening -> "Tap to stop"
            else -> "Tap to start"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = OnBackgroundMuted,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun CurrentTextCard(
    text: String,
    isListening: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Recognized Text",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (isListening) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Error)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Listening...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Error
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            if (text.isEmpty() && isListening) {
                Text(
                    text = "Speak now...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnBackgroundSubtle,
                    fontStyle = FontStyle.Italic
                )
            } else {
                Text(
                    text = text.ifEmpty { "No text recognized yet" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (text.isEmpty()) OnBackgroundSubtle else MaterialTheme.colorScheme.onSurface,
                    lineHeight = 24.sp
                )
            }
        }
    }
}

@Composable
private fun InstructionsCard(isConnected: Boolean) {
    if (!isConnected) {
        StatusCard(
            title = "Not Connected",
            message = "Connect to a device to start using speech recognition",
            icon = Icons.Default.BluetoothDisabled,
            iconTint = OnBackgroundSubtle
        )
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Ready to listen",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Tap the microphone button to start speech recognition",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnBackgroundMuted,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    ProntafonTheme {
        // Preview would need mock ViewModel
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
        ) {
            Text("Home Screen Preview")
        }
    }
}
