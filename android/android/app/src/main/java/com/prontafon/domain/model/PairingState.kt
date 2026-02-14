package com.prontafon.domain.model

/**
 * Sealed class representing BLE pairing states.
 * Provides type-safe state management for the pairing process.
 */
sealed class PairingState {
    /**
     * Device is not paired
     */
    data object NotPaired : PairingState()

    /**
     * Pairing is in progress
     * @param deviceAddress Address of the device being paired
     * @param progress Optional progress indicator (0.0 - 1.0)
     */
    data class Pairing(
        val deviceAddress: String,
        val progress: Float? = null
    ) : PairingState()

    /**
     * Device is successfully paired
     * @param deviceAddress Address of the paired device
     * @param deviceName Name of the paired device
     * @param pairedAt Timestamp when pairing completed
     */
    data class Paired(
        val deviceAddress: String,
        val deviceName: String,
        val pairedAt: Long = System.currentTimeMillis()
    ) : PairingState()

    /**
     * Pairing failed
     * @param error Error message describing the failure
     * @param errorCode Optional error code for categorization
     * @param deviceAddress Optional address of device that failed to pair
     */
    data class Failed(
        val error: String,
        val errorCode: Int? = null,
        val deviceAddress: String? = null
    ) : PairingState()

    /**
     * Whether pairing is currently in progress
     */
    val isPairing: Boolean
        get() = this is Pairing

    /**
     * Whether device is paired
     */
    val isPaired: Boolean
        get() = this is Paired

    /**
     * Whether pairing can be attempted
     */
    val canPair: Boolean
        get() = this is NotPaired || this is Failed

    /**
     * Human-readable display text for the current state
     */
    val displayText: String
        get() = when (this) {
            is NotPaired -> "Not paired"
            is Pairing -> "Pairing with device..."
            is Paired -> "Paired with $deviceName"
            is Failed -> "Pairing failed: $error"
        }
}
