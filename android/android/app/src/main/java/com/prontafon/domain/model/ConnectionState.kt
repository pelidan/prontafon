package com.prontafon.domain.model

/**
 * Bluetooth connection state enumeration.
 * Matches Flutter's ConnectionState enum.
 */
enum class BtConnectionState {
    DISCONNECTED,
    CONNECTING,
    PAIRING,          // Added: Device connected, waiting for pairing to complete
    CONNECTED,        // Device fully connected AND paired
    AWAITING_PAIRING, // Deprecated: use PAIRING instead (kept for compatibility)
    RECONNECTING,
    FAILED;

    companion object {
        /**
         * Parse connection state from string (for serialization)
         */
        fun fromString(value: String): BtConnectionState {
            return entries.find { it.name.equals(value, ignoreCase = true) }
                ?: DISCONNECTED
        }
    }
}

/**
 * Extension: Check if currently connected
 */
val BtConnectionState.isConnected: Boolean
    get() = this == BtConnectionState.CONNECTED

/**
 * Extension: Check if connection is in progress
 */
val BtConnectionState.isConnecting: Boolean
    get() = this == BtConnectionState.CONNECTING ||
            this == BtConnectionState.RECONNECTING ||
            this == BtConnectionState.PAIRING ||
            this == BtConnectionState.AWAITING_PAIRING

/**
 * Extension: Check if connection attempt is allowed
 */
val BtConnectionState.canConnect: Boolean
    get() = this == BtConnectionState.DISCONNECTED ||
            this == BtConnectionState.FAILED

/**
 * Extension: Human-readable display text
 */
val BtConnectionState.displayText: String
    get() = when (this) {
        BtConnectionState.DISCONNECTED -> "Disconnected"
        BtConnectionState.CONNECTING -> "Connecting..."
        BtConnectionState.PAIRING -> "Pairing..."
        BtConnectionState.CONNECTED -> "Connected"
        BtConnectionState.AWAITING_PAIRING -> "Awaiting Pairing..."
        BtConnectionState.RECONNECTING -> "Reconnecting..."
        BtConnectionState.FAILED -> "Connection Failed"
    }

/**
 * Extension: Icon resource name (for UI binding)
 */
val BtConnectionState.iconName: String
    get() = when (this) {
        BtConnectionState.DISCONNECTED -> "bluetooth_disabled"
        BtConnectionState.CONNECTING -> "bluetooth_searching"
        BtConnectionState.PAIRING -> "bluetooth_searching"
        BtConnectionState.CONNECTED -> "bluetooth_connected"
        BtConnectionState.AWAITING_PAIRING -> "bluetooth_searching"
        BtConnectionState.RECONNECTING -> "bluetooth_searching"
        BtConnectionState.FAILED -> "bluetooth_disabled"
    }

/**
 * Extension: Whether to show loading indicator
 */
val BtConnectionState.showProgress: Boolean
    get() = isConnecting
