package com.prontafon.util.crypto

import android.util.Base64
import com.prontafon.data.model.EncryptedMessage
import com.prontafon.domain.model.Message
import com.prontafon.domain.model.MessageType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MessageEncryption provides high-level encryption/decryption for Message objects.
 * 
 * This class handles:
 * - Message encryption (Message → EncryptedMessage)
 * - Message decryption (EncryptedMessage → Message)
 * - Integration with domain models
 * - Checksum verification and signing
 * 
 * Usage:
 * ```
 * val encrypted = messageEncryption.encryptMessage(message, key)
 * val decrypted = messageEncryption.decryptMessage(encrypted, key)
 * ```
 */
@Singleton
class MessageEncryption @Inject constructor(
    private val cryptoManager: CryptoManager
) {
    
    /**
     * Encrypts a message payload and calculates checksum.
     * 
     * Process:
     * 1. Encrypt the message payload using AES-256-GCM
     * 2. Update message with encrypted payload
     * 3. Calculate checksum over encrypted payload
     * 4. Set checksum on message
     * 
     * Note: The original message is modified in place.
     * 
     * @param message Message to encrypt (modified in place)
     * @param key 32-byte encryption key
     * @return Result<Unit> indicating success or failure
     */
    fun encryptMessage(message: Message, key: ByteArray): Result<Unit> {
        return try {
            // Skip encryption for pairing messages
            if (!message.shouldEncrypt) {
                // Still calculate checksum for pairing messages
                return signMessage(message, key)
            }
            
            // Encrypt payload
            val encryptResult = cryptoManager.encrypt(message.payload, key)
            if (encryptResult.isFailure) {
                return Result.failure(
                    encryptResult.exceptionOrNull() 
                        ?: CryptoException("Encryption failed with unknown error")
                )
            }
            
            val encryptedPayload = encryptResult.getOrThrow()
            message.payload = encryptedPayload
            
            // Sign with encrypted payload
            signMessage(message, key)
        } catch (e: Exception) {
            Result.failure(CryptoException("Message encryption failed: ${e.message}", e))
        }
    }
    
    /**
     * Decrypts a message payload and verifies checksum.
     * 
     * Process:
     * 1. Verify checksum over encrypted payload
     * 2. If valid, decrypt payload
     * 3. Update message with decrypted payload
     * 
     * Note: The original message is modified in place.
     * 
     * @param message Message to decrypt (modified in place)
     * @param key 32-byte encryption key
     * @return Result<Unit> indicating success or failure
     */
    fun decryptMessage(message: Message, key: ByteArray): Result<Unit> {
        return try {
            // Verify checksum first
            val verifyResult = verifyMessage(message, key)
            if (verifyResult.isFailure) {
                return Result.failure(
                    verifyResult.exceptionOrNull() 
                        ?: CryptoException("Checksum verification failed")
                )
            }
            
            val isValid = verifyResult.getOrThrow()
            if (!isValid) {
                return Result.failure(CryptoException("Message checksum verification failed - message may be tampered"))
            }
            
            // Skip decryption for pairing messages
            if (!message.shouldEncrypt) {
                return Result.success(Unit)
            }
            
            // Decrypt payload
            val decryptResult = cryptoManager.decrypt(message.payload, key)
            if (decryptResult.isFailure) {
                return Result.failure(
                    decryptResult.exceptionOrNull() 
                        ?: CryptoException("Decryption failed with unknown error")
                )
            }
            
            val decryptedPayload = decryptResult.getOrThrow()
            message.payload = decryptedPayload
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(CryptoException("Message decryption failed: ${e.message}", e))
        }
    }
    
    /**
     * Signs a message by calculating and setting its checksum.
     * 
     * The checksum is calculated over:
     * - version
     * - msgType
     * - payload (may be plaintext or encrypted)
     * - timestamp
     * - shared secret (key)
     * 
     * @param message Message to sign (modified in place)
     * @param key 32-byte encryption key
     * @return Result<Unit> indicating success or failure
     */
    fun signMessage(message: Message, key: ByteArray): Result<Unit> {
        return try {
            // Use the centralized checksum implementation on CryptoManager (companion object)
            val checksum = CryptoManager.calculateChecksum(
                version = message.version,
                msgType = message.messageType.value,
                payload = message.payload,
                timestamp = message.timestamp,
                secret = key
            )

            message.checksum = checksum
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(CryptoException("Message signing failed: ${e.message}", e))
        }
    }
    
    /**
     * Verifies a message's checksum.
     * 
     * @param message Message to verify
     * @param key 32-byte encryption key
     * @return Result containing true if checksum is valid, false otherwise
     */
    fun verifyMessage(message: Message, key: ByteArray): Result<Boolean> {
        return try {
            if (message.checksum.isEmpty()) {
                return Result.success(false)
            }
            
            cryptoManager.verifyChecksum(
                expectedChecksum = message.checksum,
                version = message.version,
                msgType = message.messageType.value,
                payload = message.payload,
                timestamp = message.timestamp,
                secret = key
            )
        } catch (e: Exception) {
            Result.failure(CryptoException("Message verification failed: ${e.message}", e))
        }
    }
    
    /**
     * Encrypts message and signs it in one operation.
     * Convenience method that combines encryptMessage.
     * 
     * @param message Message to process (modified in place)
     * @param key 32-byte encryption key
     * @return Result<Unit> indicating success or failure
     */
    fun encryptAndSign(message: Message, key: ByteArray): Result<Unit> {
        return encryptMessage(message, key)
    }
    
    /**
     * Verifies and decrypts message in one operation.
     * Convenience method that combines decryptMessage.
     * 
     * @param message Message to process (modified in place)
     * @param key 32-byte encryption key
     * @return Result<Unit> indicating success or failure
     */
    fun verifyAndDecrypt(message: Message, key: ByteArray): Result<Unit> {
        return decryptMessage(message, key)
    }
    
    /**
     * Creates an encrypted message from a plaintext message.
     * This creates a copy and doesn't modify the original.
     * 
     * @param message Original message to encrypt
     * @param key 32-byte encryption key
     * @return Result containing encrypted message copy
     */
    fun createEncryptedCopy(message: Message, key: ByteArray): Result<Message> {
        return try {
            val copy = message.copy()
            val result = encryptMessage(copy, key)
            if (result.isFailure) {
                return Result.failure(
                    result.exceptionOrNull() 
                        ?: CryptoException("Failed to create encrypted copy")
                )
            }
            Result.success(copy)
        } catch (e: Exception) {
            Result.failure(CryptoException("Failed to create encrypted copy: ${e.message}", e))
        }
    }
    
    /**
     * Creates a decrypted message from an encrypted message.
     * This creates a copy and doesn't modify the original.
     * 
     * @param message Encrypted message to decrypt
     * @param key 32-byte encryption key
     * @return Result containing decrypted message copy
     */
    fun createDecryptedCopy(message: Message, key: ByteArray): Result<Message> {
        return try {
            val copy = message.copy()
            val result = decryptMessage(copy, key)
            if (result.isFailure) {
                return Result.failure(
                    result.exceptionOrNull() 
                        ?: CryptoException("Failed to create decrypted copy")
                )
            }
            Result.success(copy)
        } catch (e: Exception) {
            Result.failure(CryptoException("Failed to create decrypted copy: ${e.message}", e))
        }
    }
    
    /**
     * Decodes a Base64-encoded key from a paired device.
     * 
     * @param base64Key Base64-encoded encryption key
     * @return Result containing decoded key bytes
     */
    fun decodeKey(base64Key: String): Result<ByteArray> {
        return try {
            val key = Base64.decode(base64Key, Base64.NO_WRAP)
            if (key.size != 32) {
                return Result.failure(
                    CryptoException("Invalid key size: ${key.size} bytes, expected 32")
                )
            }
            Result.success(key)
        } catch (e: Exception) {
            Result.failure(CryptoException("Failed to decode key: ${e.message}", e))
        }
    }
    
    /**
     * Encodes a key to Base64 for storage.
     * 
     * @param key 32-byte encryption key
     * @return Result containing Base64-encoded key
     */
    fun encodeKey(key: ByteArray): Result<String> {
        return try {
            if (key.size != 32) {
                return Result.failure(
                    CryptoException("Invalid key size: ${key.size} bytes, expected 32")
                )
            }
            Result.success(Base64.encodeToString(key, Base64.NO_WRAP))
        } catch (e: Exception) {
            Result.failure(CryptoException("Failed to encode key: ${e.message}", e))
        }
    }
}
