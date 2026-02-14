package com.prontafon.util.crypto

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import com.prontafon.data.model.PairedDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SecureStorageManager handles secure storage of sensitive data.
 * 
 * Uses Android's EncryptedSharedPreferences which provides:
 * - AES-256-SIV for key encryption (deterministic, allows key lookup)
 * - AES-256-GCM for value encryption (provides confidentiality + integrity)
 * - Keys stored in Android Keystore (hardware-backed on supported devices)
 * 
 * Features:
 * - Store/retrieve shared secrets
 * - Store/retrieve paired devices
 * - Thread-safe operations with mutex
 * - Hilt integration with @Singleton
 * - Context injection
 * 
 * All operations are thread-safe and run on IO dispatcher.
 */
@Singleton
class SecureStorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager
) {
    
    companion object {
        private const val PREFS_FILENAME = "prontafon_secure_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PAIRED_DEVICES = "paired_devices"
        private const val KEY_PAIRED_DEVICE_PREFIX = "paired_device_"
        private const val KEY_SHARED_SECRET_PREFIX = "shared_secret_"
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    
    private val mutex = Mutex()
    
    /**
     * Lazy initialization of encrypted preferences.
     * This may take some time on first access due to key generation.
     */
    private val prefs: SharedPreferences by lazy {
        createEncryptedPreferences()
    }
    
    /**
     * Creates EncryptedSharedPreferences with AES-256-GCM encryption.
     */
    private fun createEncryptedPreferences(): SharedPreferences {
        // Note: In security-crypto 1.0.0, the API doesn't use MasterKey directly.
        // The library creates its own master key internally.
        return EncryptedSharedPreferences.create(
            PREFS_FILENAME,
            "_androidx_security_master_key_",
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    /**
     * Gets the device ID, generating one if it doesn't exist.
     * 
     * Preference order:
     * 1. Previously stored device ID (for consistency)
     * 2. Android ID (unique per device/app combination)
     * 3. Generated random ID (fallback)
     * 
     * @return Device identifier string
     */
    @SuppressLint("HardwareIds")
    suspend fun getDeviceId(): Result<String> = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                // Check for existing stored ID
                prefs.getString(KEY_DEVICE_ID, null)?.let { 
                    return@withContext Result.success(it) 
                }
                
                // Try to get Android ID
                val androidId = try {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                        ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } // Filter known bad value
                } catch (e: Exception) {
                    null
                }
                
                // Use Android ID or generate random
                val deviceId = androidId ?: cryptoManager.generateDeviceId()
                
                // Store for future use
                prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
                
                Result.success(deviceId)
            }
        } catch (e: Exception) {
            Result.failure(SecureStorageException("Failed to get device ID: ${e.message}", e))
        }
    }
    
    /**
     * Stores a shared secret for a device address.
     * 
     * @param address Bluetooth address or device identifier
     * @param secret Base64-encoded shared secret
     * @return Result indicating success or failure
     */
    suspend fun storeSharedSecret(address: String, secret: String): Result<Unit> = 
        withContext(Dispatchers.IO) {
            try {
                if (address.isBlank()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Address cannot be blank")
                    )
                }
                if (secret.isBlank()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Secret cannot be blank")
                    )
                }
                
                mutex.withLock {
                    val key = "$KEY_SHARED_SECRET_PREFIX$address"
                    prefs.edit()
                        .putString(key, secret)
                        .apply()
                }
                
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(SecureStorageException("Failed to store shared secret: ${e.message}", e))
            }
        }
    
    /**
     * Retrieves a shared secret for a device address.
     * 
     * @param address Bluetooth address or device identifier
     * @return Result containing Base64-encoded shared secret, or null if not found
     */
    suspend fun getSharedSecret(address: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            if (address.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Address cannot be blank")
                )
            }
            
            mutex.withLock {
                val key = "$KEY_SHARED_SECRET_PREFIX$address"
                val secret = prefs.getString(key, null)
                Result.success(secret)
            }
        } catch (e: Exception) {
            Result.failure(SecureStorageException("Failed to get shared secret: ${e.message}", e))
        }
    }
    
    /**
     * Stores a paired device.
     * If a device with the same address exists, it will be updated.
     * 
     * @param device PairedDevice to store
     * @return Result indicating success or failure
     */
    suspend fun storePairedDevice(device: PairedDevice): Result<Unit> = 
        withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val key = "$KEY_PAIRED_DEVICE_PREFIX${device.address}"
                    val deviceJson = json.encodeToString(device)
                    
                    prefs.edit()
                        .putString(key, deviceJson)
                        .apply()
                    
                    // Update device list index
                    updateDeviceIndex(device.address, add = true)
                }
                
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(SecureStorageException("Failed to store paired device: ${e.message}", e))
            }
        }
    
    /**
     * Retrieves all paired devices.
     * 
     * @return Result containing list of paired devices, empty if none
     */
    suspend fun getPairedDevices(): Result<List<PairedDevice>> = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                val addresses = getDeviceAddresses()
                
                val devices = addresses.mapNotNull { address ->
                    val key = "$KEY_PAIRED_DEVICE_PREFIX$address"
                    prefs.getString(key, null)?.let { deviceJson ->
                        try {
                            json.decodeFromString<PairedDevice>(deviceJson)
                        } catch (e: Exception) {
                            // Log and skip corrupted entries
                            null
                        }
                    }
                }
                
                Result.success(devices)
            }
        } catch (e: Exception) {
            Result.failure(SecureStorageException("Failed to get paired devices: ${e.message}", e))
        }
    }
    
    /**
     * Retrieves a specific paired device by address.
     * 
     * @param address Bluetooth address of the device
     * @return Result containing PairedDevice if found, null otherwise
     */
    suspend fun getPairedDevice(address: String): Result<PairedDevice?> = 
        withContext(Dispatchers.IO) {
            try {
                if (address.isBlank()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Address cannot be blank")
                    )
                }
                
                mutex.withLock {
                    val key = "$KEY_PAIRED_DEVICE_PREFIX$address"
                    val deviceJson = prefs.getString(key, null)
                    
                    val device = deviceJson?.let {
                        try {
                            json.decodeFromString<PairedDevice>(it)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    
                    Result.success(device)
                }
            } catch (e: Exception) {
                Result.failure(SecureStorageException("Failed to get paired device: ${e.message}", e))
            }
        }
    
    /**
     * Removes a paired device and its shared secret.
     * 
     * @param address Bluetooth address of the device to remove
     * @return Result indicating success or failure
     */
    suspend fun removePairedDevice(address: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (address.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Address cannot be blank")
                )
            }
            
            mutex.withLock {
                val deviceKey = "$KEY_PAIRED_DEVICE_PREFIX$address"
                val secretKey = "$KEY_SHARED_SECRET_PREFIX$address"
                
                prefs.edit()
                    .remove(deviceKey)
                    .remove(secretKey)
                    .apply()
                
                updateDeviceIndex(address, add = false)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(SecureStorageException("Failed to remove paired device: ${e.message}", e))
        }
    }
    
    /**
     * Checks if a device is paired.
     * 
     * @param address Bluetooth address to check
     * @return Result containing true if device is paired
     */
    suspend fun isPaired(address: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (address.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Address cannot be blank")
                )
            }
            
            mutex.withLock {
                val key = "$KEY_PAIRED_DEVICE_PREFIX$address"
                Result.success(prefs.contains(key))
            }
        } catch (e: Exception) {
            Result.failure(SecureStorageException("Failed to check if paired: ${e.message}", e))
        }
    }
    
    /**
     * Updates the last connected time for a device.
     * 
     * @param address Bluetooth address of the device
     * @param timestamp Connection timestamp (defaults to now)
     * @return Result indicating success or failure
     */
    suspend fun updateLastConnected(
        address: String,
        timestamp: Long = System.currentTimeMillis()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (address.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Address cannot be blank")
                )
            }
            
            mutex.withLock {
                val deviceResult = getPairedDevice(address)
                if (deviceResult.isFailure) {
                    return@withContext Result.failure(
                        deviceResult.exceptionOrNull() 
                            ?: SecureStorageException("Failed to get device for update")
                    )
                }
                
                deviceResult.getOrNull()?.let { device ->
                    val updated = device.copy(lastConnected = timestamp)
                    val key = "$KEY_PAIRED_DEVICE_PREFIX$address"
                    prefs.edit()
                        .putString(key, json.encodeToString(updated))
                        .apply()
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                SecureStorageException("Failed to update last connected: ${e.message}", e)
            )
        }
    }
    
    /**
     * Clears all stored data.
     * Use with caution - this removes all paired devices and settings.
     * 
     * @return Result indicating success or failure
     */
    suspend fun clearAll(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                prefs.edit().clear().apply()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(SecureStorageException("Failed to clear all data: ${e.message}", e))
        }
    }
    
    /**
     * Gets the number of paired devices.
     * 
     * @return Result containing count of paired devices
     */
    suspend fun getPairedDeviceCount(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                Result.success(getDeviceAddresses().size)
            }
        } catch (e: Exception) {
            Result.failure(SecureStorageException("Failed to get device count: ${e.message}", e))
        }
    }
    
    // --- Private helpers ---
    
    /**
     * Gets the set of paired device addresses from index.
     */
    private fun getDeviceAddresses(): Set<String> {
        val addressesJson = prefs.getString(KEY_PAIRED_DEVICES, null) ?: return emptySet()
        return try {
            json.decodeFromString<Set<String>>(addressesJson)
        } catch (e: Exception) {
            emptySet()
        }
    }
    
    /**
     * Updates the device address index.
     */
    private fun updateDeviceIndex(address: String, add: Boolean) {
        val addresses = getDeviceAddresses().toMutableSet()
        
        if (add) {
            addresses.add(address)
        } else {
            addresses.remove(address)
        }
        
        prefs.edit()
            .putString(KEY_PAIRED_DEVICES, json.encodeToString(addresses))
            .apply()
    }
}

/**
 * Exception thrown for secure storage operation failures.
 */
class SecureStorageException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
