package com.prontafon.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Pairing request payload sent from Android to Linux
 */
@Serializable
data class PairRequestPayload(
    @SerialName("device_id")
    val deviceId: String,

    @SerialName("device_name")
    val deviceName: String? = null,
    
    @SerialName("public_key")
    val publicKey: String
) {
    companion object {
        /**
         * Create payload from device info
         */
        fun create(deviceId: String, deviceName: String? = null, publicKey: String): PairRequestPayload {
            return PairRequestPayload(
                deviceId = deviceId,
                deviceName = deviceName ?: "Android Device",
                publicKey = publicKey
            )
        }
    }
}

/**
 * Pairing acknowledgment payload received from Linux
 */
@Serializable
data class PairAckPayload(
    @SerialName("device_id")
    val deviceId: String,

    @SerialName("status")
    val status: String,

    @SerialName("error")
    val error: String? = null,

    @SerialName("public_key")
    val publicKey: String? = null
) {
    /**
     * Parsed status enum
     */
    val pairStatus: PairStatus
        get() = PairStatus.fromString(status)

    /**
     * Check if pairing was successful
     * Note: Desktop doesn't send sharedSecret - success is determined by status only
     */
    val isSuccess: Boolean
        get() = pairStatus == PairStatus.OK

    companion object {
        /**
         * Create success response
         */
        fun success(deviceId: String, publicKey: String): PairAckPayload {
            return PairAckPayload(
                deviceId = deviceId,
                status = PairStatus.OK.value,
                publicKey = publicKey
            )
        }

        /**
         * Create error response
         */
        fun error(deviceId: String, errorMessage: String): PairAckPayload {
            return PairAckPayload(
                deviceId = deviceId,
                status = PairStatus.ERROR.value,
                error = errorMessage
            )
        }
    }
}

/**
 * Pairing status enumeration
 */
enum class PairStatus(val value: String) {
    OK("OK"),
    ERROR("ERROR");

    companion object {
        fun fromString(value: String): PairStatus {
            return entries.find { it.value.equals(value, ignoreCase = true) }
                ?: ERROR
        }
    }
}
