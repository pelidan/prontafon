package com.prontafon.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Date

/**
 * Represents a device that has been paired with the app.
 * Stored in encrypted preferences.
 */
@Serializable
data class PairedDevice(
    @SerialName("address")
    val address: String,

    @SerialName("name")
    val name: String,

    @SerialName("linux_device_id")
    val linuxDeviceId: String,

    @SerialName("shared_secret")
    val sharedSecret: String, // Base64 encoded AES key

    @SerialName("paired_at")
    val pairedAt: Long,

    @SerialName("last_connected")
    val lastConnected: Long? = null
) {
    /**
     * Display name for UI
     */
    val displayName: String
        get() = name.ifBlank { address }

    /**
     * Pairing date as Date object
     */
    val pairedDate: Date
        get() = Date(pairedAt)

    /**
     * Last connected date as Date object (null if never connected)
     */
    val lastConnectedDate: Date?
        get() = lastConnected?.let { Date(it) }

    /**
     * Whether device has been connected recently (within 24 hours)
     */
    val isRecentlyConnected: Boolean
        get() {
            val last = lastConnected ?: return false
            return System.currentTimeMillis() - last < 24 * 60 * 60 * 1000
        }

    /**
     * Days since last connection
     */
    val daysSinceConnection: Int?
        get() {
            val last = lastConnected ?: return null
            val diff = System.currentTimeMillis() - last
            return (diff / (24 * 60 * 60 * 1000)).toInt()
        }

    /**
     * Create a copy with updated last connected time
     */
    fun withLastConnected(timestamp: Long = System.currentTimeMillis()): PairedDevice {
        return copy(lastConnected = timestamp)
    }

    /**
     * Create a copy with updated name
     */
    fun withName(newName: String): PairedDevice {
        return copy(name = newName)
    }

    companion object {
        /**
         * Create a new paired device entry
         */
        fun create(
            address: String,
            name: String,
            linuxDeviceId: String,
            sharedSecret: String
        ): PairedDevice {
            return PairedDevice(
                address = address,
                name = name,
                linuxDeviceId = linuxDeviceId,
                sharedSecret = sharedSecret,
                pairedAt = System.currentTimeMillis()
            )
        }
    }
}
