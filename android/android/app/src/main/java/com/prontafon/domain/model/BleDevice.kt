package com.prontafon.domain.model

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

/**
 * BLE device information for scanning and display
 */
data class BleDeviceInfo(
    val name: String,
    val address: String,
    val rssi: Int,
    val hasProntafonService: Boolean,
    val device: BluetoothDevice
) {
    /**
     * Display name (use address if name is empty)
     */
    val displayName: String
        get() = name.ifBlank { address }

    /**
     * Whether this is a Prontafon service device
     */
    val isProntafon: Boolean
        get() = hasProntafonService || name.contains("Prontafon", ignoreCase = true)

    /**
     * Signal strength description
     */
    val signalStrength: SignalStrength
        get() = when {
            rssi >= -50 -> SignalStrength.EXCELLENT
            rssi >= -60 -> SignalStrength.GOOD
            rssi >= -70 -> SignalStrength.FAIR
            else -> SignalStrength.WEAK
        }

    /**
     * Signal strength as percentage (0-100)
     */
    val signalPercent: Int
        get() = ((100 + rssi).coerceIn(0, 100))

    /**
     * Formatted RSSI string
     */
    val rssiDisplay: String
        get() = "$rssi dBm"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleDeviceInfo) return false
        return address == other.address
    }

    override fun hashCode(): Int {
        return address.hashCode()
    }

    companion object {
        /**
         * Create from BluetoothDevice with scan result
         */
        @SuppressLint("MissingPermission")
        fun fromDevice(
            device: BluetoothDevice,
            rssi: Int = 0,
            hasProntafonService: Boolean = false
        ): BleDeviceInfo {
            return BleDeviceInfo(
                name = device.name ?: "",
                address = device.address,
                rssi = rssi,
                hasProntafonService = hasProntafonService,
                device = device
            )
        }
    }
}

/**
 * Signal strength categories for UI display
 */
enum class SignalStrength(val bars: Int, val description: String) {
    EXCELLENT(4, "Excellent"),
    GOOD(3, "Good"),
    FAIR(2, "Fair"),
    WEAK(1, "Weak");
}

/**
 * BLE scan result with timestamp
 */
data class BleScanResult(
    val device: BleDeviceInfo,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Age of scan result in milliseconds
     */
    val age: Long
        get() = System.currentTimeMillis() - timestamp

    /**
     * Whether result is stale (older than 10 seconds)
     */
    val isStale: Boolean
        get() = age > 10_000
}
