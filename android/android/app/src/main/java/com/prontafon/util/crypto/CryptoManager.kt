package com.prontafon.util.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CryptoManager handles all cryptographic operations for Prontafon.
 * 
 * Features:
 * - AES-256-GCM encryption/decryption
 * - PBKDF2 key derivation from PIN + device identifiers
 * - 12-byte nonce generation
 * - Input validation
 * - Error handling with Result<T>
 * 
 * All implementations MUST match the Flutter version exactly for cross-platform compatibility.
 */
@Singleton
class CryptoManager @Inject constructor() {
    
    companion object {
        // Crypto constants matching Flutter implementation
        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_LENGTH_BITS = 256
        private const val KEY_LENGTH_BYTES = 32
        private const val GCM_NONCE_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val AES_ALGORITHM = "AES"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SHA256_ALGORITHM = "SHA-256"
        private const val CHECKSUM_BYTES = 4
        
        // Fixed salt for PBKDF2 - MUST match Flutter implementation
        private val SALT = "prontafon_v1".toByteArray(Charsets.UTF_8)

        /**
         * Calculates a SHA-256 checksum for message integrity verification.
         *
         * The checksum is computed over:
         * version || msgType || payload || timestamp || secret
         *
         * Only the first 4 bytes (8 hex characters) are used as the checksum.
         *
         * @param version Protocol version number
         * @param msgType Message type string (e.g., "TEXT", "COMMAND")
         * @param payload Message payload (may be encrypted)
         * @param timestamp Unix timestamp in milliseconds
         * @param secret Shared secret (derived key)
         * @return 8-character hex string
         */
        fun calculateChecksum(
            version: Int,
            msgType: String,
            payload: String,
            timestamp: Long,
            secret: ByteArray
        ): String {
            val versionBytes = version.toString().toByteArray(Charsets.UTF_8)
            val msgTypeBytes = msgType.toByteArray(Charsets.UTF_8)
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            val timestampBytes = timestamp.toString().toByteArray(Charsets.UTF_8)

            val totalLength = versionBytes.size + msgTypeBytes.size +
                    payloadBytes.size + timestampBytes.size + secret.size

            val result = ByteArray(totalLength)
            var offset = 0

            System.arraycopy(versionBytes, 0, result, offset, versionBytes.size)
            offset += versionBytes.size

            System.arraycopy(msgTypeBytes, 0, result, offset, msgTypeBytes.size)
            offset += msgTypeBytes.size

            System.arraycopy(payloadBytes, 0, result, offset, payloadBytes.size)
            offset += payloadBytes.size

            System.arraycopy(timestampBytes, 0, result, offset, timestampBytes.size)
            offset += timestampBytes.size

            System.arraycopy(secret, 0, result, offset, secret.size)

            // Compute SHA-256
            val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
            val hash = digest.digest(result)

            // Take first 4 bytes and convert to hex (lowercase)
            return hash.take(CHECKSUM_BYTES)
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }
    
    private val secureRandom = SecureRandom()
    
    /**
     * Derives an AES-256 key using PBKDF2-HMAC-SHA256.
     * 
     * The key derivation combines:
     * - User-provided PIN (4-6 digits)
     * - Android device ID (unique per device/app combination)
     * - Linux machine ID (identifies the paired computer)
     * 
     * @param pin User-provided PIN (typically 4-6 digits)
     * @param androidId Android device identifier
     * @param linuxId Linux machine identifier from paired device
     * @return Result containing 32-byte (256-bit) derived key
     */
    fun deriveKey(pin: String, androidId: String, linuxId: String): Result<ByteArray> {
        return try {
            if (pin.isBlank()) {
                return Result.failure(IllegalArgumentException("PIN cannot be blank"))
            }
            if (androidId.isBlank()) {
                return Result.failure(IllegalArgumentException("Android ID cannot be blank"))
            }
            if (linuxId.isBlank()) {
                return Result.failure(IllegalArgumentException("Linux ID cannot be blank"))
            }
            
            val password = "$pin$androidId$linuxId"
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val spec = PBEKeySpec(
                password.toCharArray(),
                SALT,
                PBKDF2_ITERATIONS,
                KEY_LENGTH_BITS
            )
            
            val key = try {
                factory.generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
            
            if (key.size != KEY_LENGTH_BYTES) {
                return Result.failure(
                    IllegalStateException("Derived key is ${key.size} bytes, expected $KEY_LENGTH_BYTES")
                )
            }
            
            Result.success(key)
        } catch (e: Exception) {
            Result.failure(CryptoException("Key derivation failed: ${e.message}", e))
        }
    }

    /**
     * Derives an AES-256 key from ECDH shared secret and device identifiers.
     * The shared secret provides cryptographic strength, device IDs provide binding.
     */
    fun deriveKeyFromEcdh(
        sharedSecret: ByteArray,
        androidId: String,
        linuxId: String
    ): Result<ByteArray> {
        return try {
            if (sharedSecret.size != 32) {
                return Result.failure(
                    IllegalArgumentException("Shared secret must be 32 bytes, got ${sharedSecret.size}")
                )
            }
            // Convert shared secret to hex for consistent cross-platform representation
            val sharedSecretHex = sharedSecret.joinToString("") { "%02x".format(it) }
            val password = "$sharedSecretHex$androidId$linuxId"
            
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val spec = PBEKeySpec(
                password.toCharArray(),
                SALT,
                PBKDF2_ITERATIONS,
                KEY_LENGTH_BITS
            )
            
            val key = try {
                factory.generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
            
            Result.success(key)
        } catch (e: Exception) {
            Result.failure(CryptoException("Key derivation from ECDH failed: ${e.message}", e))
        }
    }
    
    /**
     * Encrypts plaintext using AES-256-GCM.
     * 
     * Output format: Base64(nonce || ciphertext || tag)
     * - nonce: 12 bytes (randomly generated)
     * - ciphertext: variable length (same as plaintext)
     * - tag: 16 bytes (GCM authentication tag, appended by cipher)
     * 
     * @param plaintext The text to encrypt
     * @param key 32-byte AES key
     * @return Result containing Base64-encoded encrypted data
     */
    fun encrypt(plaintext: String, key: ByteArray): Result<String> {
        return try {
            if (key.size != KEY_LENGTH_BYTES) {
                return Result.failure(
                    IllegalArgumentException("Key must be $KEY_LENGTH_BYTES bytes, got ${key.size}")
                )
            }
            
            // Generate random nonce (12 bytes for GCM)
            val nonce = ByteArray(GCM_NONCE_LENGTH)
            secureRandom.nextBytes(nonce)
            
            // Initialize cipher
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            val secretKey = SecretKeySpec(key, AES_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
            
            // Encrypt (ciphertext includes auth tag at the end)
            val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
            val ciphertextWithTag = cipher.doFinal(plaintextBytes)
            
            // Combine: nonce || ciphertext || tag
            val combined = ByteArray(nonce.size + ciphertextWithTag.size)
            System.arraycopy(nonce, 0, combined, 0, nonce.size)
            System.arraycopy(ciphertextWithTag, 0, combined, nonce.size, ciphertextWithTag.size)
            
            Result.success(Base64.encodeToString(combined, Base64.NO_WRAP))
        } catch (e: Exception) {
            Result.failure(CryptoException("Encryption failed: ${e.message}", e))
        }
    }
    
    /**
     * Decrypts AES-256-GCM encrypted data.
     * 
     * Input format: Base64(nonce || ciphertext || tag)
     * 
     * @param ciphertext Base64-encoded encrypted data
     * @param key 32-byte AES key
     * @return Result containing decrypted plaintext
     */
    fun decrypt(ciphertext: String, key: ByteArray): Result<String> {
        return try {
            if (key.size != KEY_LENGTH_BYTES) {
                return Result.failure(
                    IllegalArgumentException("Key must be $KEY_LENGTH_BYTES bytes, got ${key.size}")
                )
            }
            
            val combined = try {
                Base64.decode(ciphertext, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                return Result.failure(CryptoException("Invalid base64 encoding", e))
            }
            
            val minSize = GCM_NONCE_LENGTH + (GCM_TAG_LENGTH / 8)
            if (combined.size < minSize) {
                return Result.failure(
                    CryptoException("Ciphertext too short: ${combined.size} bytes, minimum $minSize")
                )
            }
            
            // Extract nonce
            val nonce = combined.copyOfRange(0, GCM_NONCE_LENGTH)
            
            // Extract ciphertext + tag
            val ciphertextWithTag = combined.copyOfRange(GCM_NONCE_LENGTH, combined.size)
            
            // Initialize cipher
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            val secretKey = SecretKeySpec(key, AES_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            
            // Decrypt
            val plaintextBytes = try {
                cipher.doFinal(ciphertextWithTag)
            } catch (e: Exception) {
                return Result.failure(
                    CryptoException("Decryption failed - wrong key or tampered data: ${e.message}", e)
                )
            }
            
            Result.success(String(plaintextBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            if (e is CryptoException) {
                Result.failure(e)
            } else {
                Result.failure(CryptoException("Decryption failed: ${e.message}", e))
            }
        }
    }
    
    /**
     * Verifies a message checksum.
     * 
     * @return Result containing true if checksum is valid, false otherwise
     */
    fun verifyChecksum(
        expectedChecksum: String,
        version: Int,
        msgType: String,
        payload: String,
        timestamp: Long,
        secret: ByteArray
    ): Result<Boolean> {
        return try {
            val calculated = calculateChecksum(version, msgType, payload, timestamp, secret)
            Result.success(calculated.equals(expectedChecksum, ignoreCase = true))
        } catch (e: Exception) {
            Result.failure(CryptoException("Checksum verification failed: ${e.message}", e))
        }
    }
    
    /**
     * Generates a random 12-byte nonce for GCM encryption.
     * 
     * @return Base64-encoded nonce
     */
    fun generateNonce(): String {
        val nonce = ByteArray(GCM_NONCE_LENGTH)
        secureRandom.nextBytes(nonce)
        return Base64.encodeToString(nonce, Base64.NO_WRAP)
    }
    
    /**
     * Generates a random device ID.
     * Used when Android ID is not available.
     * 
     * @return 32-character hex string (16 bytes)
     */
    fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Securely wipes a byte array by filling with zeros.
     * Call this when done with sensitive key material.
     */
    fun wipeBytes(bytes: ByteArray) {
        bytes.fill(0)
    }
}

/**
 * Exception thrown for cryptographic operation failures.
 */
class CryptoException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
