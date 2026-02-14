package com.prontafon.data.model

import android.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data class for encrypted message payloads.
 * Contains the encrypted data along with nonce and authentication tag.
 */
@Serializable
data class EncryptedMessage(
    @SerialName("nonce")
    val nonce: String, // Base64 encoded nonce/IV

    @SerialName("ciphertext")
    val ciphertext: String, // Base64 encoded encrypted data

    @SerialName("tag")
    val tag: String? = null // Base64 encoded authentication tag (for AEAD)
) {
    /**
     * Decode nonce from Base64
     */
    fun decodeNonce(): ByteArray {
        return Base64.decode(nonce, Base64.NO_WRAP)
    }

    /**
     * Decode ciphertext from Base64
     */
    fun decodeCiphertext(): ByteArray {
        return Base64.decode(ciphertext, Base64.NO_WRAP)
    }

    /**
     * Decode authentication tag from Base64 (if present)
     */
    fun decodeTag(): ByteArray? {
        return tag?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    /**
     * Get total size in bytes
     */
    val sizeBytes: Int
        get() = decodeCiphertext().size + decodeNonce().size + (decodeTag()?.size ?: 0)

    /**
     * Serialize to JSON string
     */
    fun toJson(): String {
        return kotlinx.serialization.json.Json.encodeToString(serializer(), this)
    }

    companion object {
        /**
         * Create from byte arrays
         */
        fun fromBytes(
            nonce: ByteArray,
            ciphertext: ByteArray,
            tag: ByteArray? = null
        ): EncryptedMessage {
            return EncryptedMessage(
                nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                tag = tag?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            )
        }

        /**
         * Parse from JSON string
         */
        fun fromJson(json: String): EncryptedMessage {
            return kotlinx.serialization.json.Json.decodeFromString(serializer(), json)
        }

        /**
         * Create with combined ciphertext+tag (for GCM mode)
         */
        fun fromCombinedCiphertext(
            nonce: ByteArray,
            ciphertextWithTag: ByteArray,
            tagSize: Int = 16
        ): EncryptedMessage {
            val ciphertext = ciphertextWithTag.copyOfRange(0, ciphertextWithTag.size - tagSize)
            val tag = ciphertextWithTag.copyOfRange(ciphertextWithTag.size - tagSize, ciphertextWithTag.size)
            return fromBytes(nonce, ciphertext, tag)
        }

        /**
         * Maximum encrypted message size (in bytes)
         */
        const val MAX_SIZE_BYTES = 512 * 1024 // 512 KB
    }

    /**
     * Get combined ciphertext + tag (for GCM mode)
     */
    fun getCombinedCiphertext(): ByteArray {
        val ciphertextBytes = decodeCiphertext()
        val tagBytes = decodeTag()
        
        return if (tagBytes != null) {
            ciphertextBytes + tagBytes
        } else {
            ciphertextBytes
        }
    }

    /**
     * Validate encrypted message structure
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        try {
            val nonceBytes = decodeNonce()
            if (nonceBytes.isEmpty()) {
                errors.add("Nonce is empty")
            }
            if (nonceBytes.size != 12 && nonceBytes.size != 16) {
                errors.add("Nonce size should be 12 or 16 bytes, got ${nonceBytes.size}")
            }
        } catch (e: Exception) {
            errors.add("Invalid nonce encoding: ${e.message}")
        }
        
        try {
            val ciphertextBytes = decodeCiphertext()
            if (ciphertextBytes.isEmpty()) {
                errors.add("Ciphertext is empty")
            }
            if (ciphertextBytes.size > MAX_SIZE_BYTES) {
                errors.add("Ciphertext exceeds maximum size")
            }
        } catch (e: Exception) {
            errors.add("Invalid ciphertext encoding: ${e.message}")
        }
        
        if (tag != null) {
            try {
                val tagBytes = decodeTag()
                if (tagBytes != null && tagBytes.size != 16) {
                    errors.add("Authentication tag should be 16 bytes, got ${tagBytes.size}")
                }
            } catch (e: Exception) {
                errors.add("Invalid tag encoding: ${e.message}")
            }
        }
        
        return errors
    }

    /**
     * Check if encrypted message is valid
     */
    val isValid: Boolean
        get() = validate().isEmpty()
}
