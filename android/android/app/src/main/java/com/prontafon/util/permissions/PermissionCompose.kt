package com.prontafon.util.permissions

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Composable function to handle permission requests with Compose.
 * This provides a convenient way to request permissions and handle the results in a Compose UI.
 *
 * Usage:
 * ```
 * val permissionState = rememberPermissionState(
 *     permissions = listOf(Manifest.permission.RECORD_AUDIO),
 *     onPermissionsResult = { allGranted ->
 *         if (allGranted) {
 *             // All permissions granted
 *         }
 *     }
 * )
 *
 * if (!permissionState.allGranted) {
 *     Button(onClick = { permissionState.launchPermissionRequest() }) {
 *         Text("Grant Permissions")
 *     }
 * }
 * ```
 *
 * @param permissions List of permissions to request
 * @param onPermissionsResult Callback invoked with true if all permissions are granted
 */
@Composable
fun rememberPermissionState(
    permissions: List<String>,
    onPermissionsResult: (allGranted: Boolean) -> Unit = {}
): PermissionRequestState {
    val context = LocalContext.current
    val activity = context as? Activity
    
    // Track if all permissions are granted
    var allGranted by remember { mutableStateOf(false) }
    var shouldShowRationale by remember { mutableStateOf(false) }
    
    // Check permissions on lifecycle resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, permissions) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = permissions.all { permission ->
                    ActivityCompat.checkSelfPermission(
                        context,
                        permission
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                allGranted = granted
                
                shouldShowRationale = activity?.let { act ->
                    permissions.any { permission ->
                        ActivityCompat.shouldShowRequestPermissionRationale(act, permission)
                    }
                } ?: false
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Create permission launcher
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGrantedNow = results.values.all { it }
        allGranted = allGrantedNow
        onPermissionsResult(allGrantedNow)
    }
    
    return remember(permissions, allGranted, shouldShowRationale) {
        PermissionRequestState(
            permissions = permissions,
            allGranted = allGranted,
            shouldShowRationale = shouldShowRationale,
            launchPermissionRequest = {
                launcher.launch(permissions.toTypedArray())
            }
        )
    }
}

/**
 * State holder for permission requests.
 *
 * @property permissions List of permissions being requested
 * @property allGranted Whether all permissions are granted
 * @property shouldShowRationale Whether rationale should be shown before requesting
 * @property launchPermissionRequest Function to launch the permission request
 */
data class PermissionRequestState(
    val permissions: List<String>,
    val allGranted: Boolean,
    val shouldShowRationale: Boolean,
    val launchPermissionRequest: () -> Unit
)
