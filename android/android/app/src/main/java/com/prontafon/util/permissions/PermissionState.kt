package com.prontafon.util.permissions

import android.Manifest

/**
 * Represents the status of a single permission.
 */
enum class PermissionStatus {
    /** Permission has been granted */
    GRANTED,
    
    /** Permission has been denied but can be requested again */
    DENIED,
    
    /** Permission has been denied permanently (user selected "Don't ask again") */
    DENIED_PERMANENTLY
}

/**
 * Groups of permissions required by the app.
 */
enum class PermissionGroup {
    /** Bluetooth-related permissions (varies by API level) */
    BLUETOOTH,
    
    /** Audio recording permission */
    AUDIO,
    
    /** Location permissions (required for BLE on API < 31) */
    LOCATION
}

/**
 * Data class representing the state of all permissions in the app.
 *
 * @property bluetooth Status of Bluetooth permissions
 * @property audio Status of audio recording permission
 * @property location Status of location permission
 */
data class PermissionState(
    val bluetooth: Map<String, PermissionStatus>,
    val audio: Map<String, PermissionStatus>,
    val location: Map<String, PermissionStatus>
) {
    /**
     * Checks if all Bluetooth permissions are granted.
     * The required permissions vary by API level:
     * - API 31+: BLUETOOTH_SCAN, BLUETOOTH_CONNECT
     * - API < 31: ACCESS_FINE_LOCATION (required for BLE scanning)
     */
    fun hasBluetoothPermissions(): Boolean {
        return bluetooth.values.all { it == PermissionStatus.GRANTED }
    }
    
    /**
     * Checks if audio recording permission is granted.
     */
    fun hasAudioPermission(): Boolean {
        return audio.values.all { it == PermissionStatus.GRANTED }
    }
    
    /**
     * Checks if location permissions are granted.
     * Not required for BLE scanning on API 33+, but kept for potential future use.
     */
    fun hasLocationPermission(): Boolean {
        return location.values.all { it == PermissionStatus.GRANTED }
    }
    
    /**
     * Checks if all required permissions for the app are granted.
     * This includes Bluetooth and Audio permissions.
     * Location is only required on API < 31.
     */
    fun hasAllRequiredPermissions(): Boolean {
        return hasBluetoothPermissions() && hasAudioPermission()
    }
    
    /**
     * Returns a list of all permissions that are denied (but can be requested again).
     */
    fun getDeniedPermissions(): List<String> {
        val denied = mutableListOf<String>()
        bluetooth.forEach { (permission, status) ->
            if (status == PermissionStatus.DENIED) denied.add(permission)
        }
        audio.forEach { (permission, status) ->
            if (status == PermissionStatus.DENIED) denied.add(permission)
        }
        location.forEach { (permission, status) ->
            if (status == PermissionStatus.DENIED) denied.add(permission)
        }
        return denied
    }
    
    /**
     * Returns a list of all permissions that are permanently denied.
     */
    fun getPermanentlyDeniedPermissions(): List<String> {
        val denied = mutableListOf<String>()
        bluetooth.forEach { (permission, status) ->
            if (status == PermissionStatus.DENIED_PERMANENTLY) denied.add(permission)
        }
        audio.forEach { (permission, status) ->
            if (status == PermissionStatus.DENIED_PERMANENTLY) denied.add(permission)
        }
        location.forEach { (permission, status) ->
            if (status == PermissionStatus.DENIED_PERMANENTLY) denied.add(permission)
        }
        return denied
    }
    
    /**
     * Checks if any permissions are permanently denied.
     */
    fun hasPermanentlyDeniedPermissions(): Boolean {
        return getPermanentlyDeniedPermissions().isNotEmpty()
    }
    
    companion object {
        /**
         * Returns the list of Bluetooth permissions required for the current API level.
         */
        fun getRequiredBluetoothPermissions(): List<String> {
            return listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        }
        
        /**
         * Returns the list of audio permissions required.
         */
        fun getRequiredAudioPermissions(): List<String> {
            return listOf(Manifest.permission.RECORD_AUDIO)
        }
        
        /**
         * Returns the list of location permissions required for the current API level.
         * Only required for BLE scanning on API < 31.
         */
        fun getRequiredLocationPermissions(): List<String> {
            // Location not required for BLE on API 33+
            return emptyList()
        }
        
        /**
         * Returns all permissions required by the app for the current API level.
         */
        fun getAllRequiredPermissions(): List<String> {
            return getRequiredBluetoothPermissions() +
                   getRequiredAudioPermissions() +
                   getRequiredLocationPermissions()
        }
    }
}
