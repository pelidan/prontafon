package com.prontafon

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import android.util.Log
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.prontafon.presentation.navigation.NavGraph
import com.prontafon.presentation.navigation.Screen
import com.prontafon.presentation.theme.ProntafonTheme
import com.prontafon.util.permissions.PermissionState
import com.prontafon.util.permissions.rememberPermissionState
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main entry point Activity for Prontafon.
 * 
 * Responsibilities:
 * - Set up navigation
 * - Handle system back button
 * - Request initial permissions
 * - Check Bluetooth enabled state
 * - Inject dependencies via Hilt
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothManager?.adapter
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d(TAG, "Bluetooth enabled by user")
        } else {
            Log.w(TAG, "User declined to enable Bluetooth")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge display
        enableEdgeToEdge()
        
        // Check if Bluetooth is available
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device")
        }
        
        setContent {
            ProntafonTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainContent()
                }
            }
        }
    }

    @Composable
    private fun MainContent() {
        val navController = rememberNavController()
        
        // Check for required permissions (API 33+)
        val requiredPermissions = remember {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.RECORD_AUDIO
            )
        }
        
        var permissionsChecked by remember { mutableStateOf(false) }
        
        val permissionState = rememberPermissionState(
            permissions = requiredPermissions,
            onPermissionsResult = { allGranted ->
                if (allGranted) {
                    Log.d(TAG, "All permissions granted")
                    permissionsChecked = true
                } else {
                    Log.w(TAG, "Some permissions denied")
                    permissionsChecked = true
                }
            }
        )
        
        // Check Bluetooth state
        LaunchedEffect(Unit) {
            checkBluetoothEnabled()
        }
        
        // Determine start destination based on permissions
        val startDestination = if (permissionState.allGranted) {
            Screen.Home.route
        } else {
            Screen.Home.route // Still go to Home, but it will show permission prompts
        }
        
        // Main navigation graph
        NavGraph(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize()
        )
    }

    /**
     * Check if Bluetooth is enabled, and prompt user to enable if not
     */
    private fun checkBluetoothEnabled() {
        val adapter = bluetoothAdapter ?: return
        
        if (!adapter.isEnabled) {
            Log.d(TAG, "Bluetooth is disabled, requesting enable")
            try {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBluetoothLauncher.launch(enableBtIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request Bluetooth enable", e)
            }
        } else {
            Log.d(TAG, "Bluetooth is already enabled")
        }
    }
    
    companion object {
        private const val TAG = "MainActivity"
    }
}
