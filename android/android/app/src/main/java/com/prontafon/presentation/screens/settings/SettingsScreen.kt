package com.prontafon.presentation.screens.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.prontafon.presentation.components.ConfirmDialog
import com.prontafon.presentation.components.ProntafonTopBar
import com.prontafon.presentation.theme.*
import java.util.Locale

/**
 * Settings screen for app configuration.
 * 
 * Features:
 * - Speech settings section (locale, partial results)
 * - Connection settings (auto-reconnect)
 * - Paired devices management
 * - Clear all data option
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val selectedLocale by viewModel.selectedLocale.collectAsState()
    val availableLocales by viewModel.availableLocales.collectAsState()
    val showRecognizedText by viewModel.showRecognizedText.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val backgroundListening by viewModel.backgroundListening.collectAsState()
    
    val autoReconnect by viewModel.autoReconnect.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    
    var showLocaleDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Permission launcher for POST_NOTIFICATIONS (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                viewModel.onNotificationPermissionGranted()
            } else {
                viewModel.onNotificationPermissionDenied()
            }
        }
    )
    
    // Handle navigation events
    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { event ->
            when (event) {
                is NavigationEvent.NavigateToHome -> onNavigateBack()
            }
        }
    }
    
    // Handle UI events (permission requests, messages, etc.)
    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is SettingsUiEvent.RequestNotificationPermission -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                is SettingsUiEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Long
                    )
                }
            }
        }
    }
    
    Scaffold(
        topBar = {
            ProntafonTopBar(
                title = "Settings",
                onNavigateBack = onNavigateBack
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Speech Settings Section
            SettingsSection(title = "Speech Recognition") {
                // Locale Selection
                SettingsItem(
                    title = "Language",
                    subtitle = getLocaleDisplayName(selectedLocale),
                    icon = Icons.Default.Language,
                    onClick = { showLocaleDialog = true }
                )
                
                // Show Recognized Text Toggle
                SettingsToggleItem(
                    title = "Show Recognized Text",
                    subtitle = "Show recognized text on home screen while listening",
                    icon = Icons.Default.TextFields,
                    checked = showRecognizedText,
                    onCheckedChange = viewModel::setShowRecognizedText
                )
                
                // Keep Screen On Toggle
                SettingsToggleItem(
                    title = "Keep Screen On",
                    subtitle = "Prevent screen from sleeping while listening",
                    icon = Icons.Default.ScreenLockPortrait,
                    checked = keepScreenOn,
                    onCheckedChange = viewModel::setKeepScreenOn
                )
                
                // Background Listening Toggle
                SettingsToggleItem(
                    title = "Background Listening",
                    subtitle = "Continue listening when screen is off (uses battery)",
                    icon = Icons.Default.BatteryAlert,
                    checked = backgroundListening,
                    onCheckedChange = viewModel::onBackgroundListeningToggle
                )
            }
            
            // Connection Settings Section
            SettingsSection(title = "Connection") {
                SettingsToggleItem(
                    title = "Auto Reconnect",
                    subtitle = "Automatically reconnect to last device",
                    icon = Icons.Default.BluetoothConnected,
                    checked = autoReconnect,
                    onCheckedChange = viewModel::setAutoReconnect
                )
            }
            
            // Paired Devices Section
            if (pairedDevices.isNotEmpty()) {
                SettingsSection(title = "Paired Devices") {
                    pairedDevices.forEach { device ->
                        PairedDeviceItem(
                            deviceName = device.name,
                            deviceAddress = device.address,
                            onForget = { viewModel.forgetPairedDevice(device.address) }
                        )
                    }
                }
            }
            
            // Data Management Section
            SettingsSection(title = "Data Management") {
                SettingsItem(
                    title = "Clear All Data",
                    subtitle = "Remove all paired devices and reset settings",
                    icon = Icons.Default.DeleteForever,
                    onClick = { showClearDataDialog = true },
                    isDestructive = true
                )
            }
        }
    }
    
    // Locale Selection Dialog
    if (showLocaleDialog) {
        LocaleSelectionDialog(
            currentLocale = selectedLocale,
            availableLocales = availableLocales,
            onSelectLocale = { locale ->
                viewModel.setLocale(locale)
                showLocaleDialog = false
            },
            onDismiss = { showLocaleDialog = false }
        )
    }
    
    // Clear Data Confirmation Dialog
    if (showClearDataDialog) {
        ConfirmDialog(
            title = "Clear All Data?",
            message = "This will remove all paired devices and reset all settings to defaults. This action cannot be undone.",
            confirmText = "CLEAR",
            cancelText = "CANCEL",
            onConfirm = {
                viewModel.clearAllData()
                showClearDataDialog = false
            },
            onDismiss = { showClearDataDialog = false },
            isDestructive = true
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isDestructive) Error else OnBackgroundSubtle,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (isDestructive) Error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnBackgroundSubtle
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = OnBackgroundSubtle,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = OnBackgroundSubtle,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = OnBackgroundSubtle
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnPrimary,
                checkedTrackColor = Primary
            )
        )
    }
}

@Composable
private fun PairedDeviceItem(
    deviceName: String,
    deviceAddress: String,
    onForget: () -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Bluetooth,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = deviceName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = deviceAddress,
                style = MaterialTheme.typography.bodySmall,
                color = OnBackgroundSubtle
            )
        }
        TextButton(
            onClick = { showConfirmDialog = true },
            colors = ButtonDefaults.textButtonColors(
                contentColor = Error
            )
        ) {
            Text("FORGET")
        }
    }
    
    if (showConfirmDialog) {
        ConfirmDialog(
            title = "Forget Device?",
            message = "This will remove $deviceName from your paired devices. You'll need to pair again to reconnect.",
            confirmText = "FORGET",
            cancelText = "CANCEL",
            onConfirm = {
                onForget()
                showConfirmDialog = false
            },
            onDismiss = { showConfirmDialog = false },
            isDestructive = true
        )
    }
}

@Composable
private fun LocaleSelectionDialog(
    currentLocale: String,
    availableLocales: List<Locale>,
    onSelectLocale: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select Language",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                availableLocales.forEach { locale ->
                    val localeCode = "${locale.language}-${locale.country}"
                    val isSelected = localeCode == currentLocale
                    
                    Surface(
                        onClick = { onSelectLocale(localeCode) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (isSelected) Primary.copy(alpha = 0.1f) else Color.Transparent,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = locale.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                color = if (isSelected) Primary else MaterialTheme.colorScheme.onSurface
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Primary
                )
            ) {
                Text("CLOSE")
            }
        },
        shape = MaterialTheme.shapes.large,
        containerColor = Surface,
        tonalElevation = 8.dp
    )
}

private fun getLocaleDisplayName(localeCode: String): String {
    val parts = localeCode.split("-")
    return if (parts.size == 2) {
        val locale = Locale(parts[0], parts[1])
        locale.displayName
    } else {
        localeCode
    }
}
