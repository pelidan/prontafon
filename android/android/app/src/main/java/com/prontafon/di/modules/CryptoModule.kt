package com.prontafon.di.modules

import android.content.Context
import com.prontafon.util.crypto.CryptoManager
import com.prontafon.util.crypto.MessageEncryption
import com.prontafon.util.crypto.SecureStorageManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for cryptography dependencies.
 * 
 * This module provides:
 * - CryptoManager: Core cryptographic operations
 * - MessageEncryption: High-level message encryption/decryption
 * - SecureStorageManager: Secure storage for sensitive data
 * 
 * All dependencies are singleton-scoped to ensure efficient resource usage
 * and consistent cryptographic operations throughout the app.
 */
@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {
    
    /**
     * Provides CryptoManager singleton.
     * 
     * CryptoManager handles:
     * - AES-256-GCM encryption/decryption
     * - PBKDF2 key derivation
     * - Nonce generation
     * - Checksum calculation and verification
     * 
     * @return CryptoManager instance
     */
    @Provides
    @Singleton
    fun provideCryptoManager(): CryptoManager {
        return CryptoManager()
    }
    
    /**
     * Provides MessageEncryption singleton.
     * 
     * MessageEncryption provides high-level encryption/decryption
     * operations for Message objects, integrating with domain models.
     * 
     * @param cryptoManager CryptoManager instance for low-level operations
     * @return MessageEncryption instance
     */
    @Provides
    @Singleton
    fun provideMessageEncryption(
        cryptoManager: CryptoManager
    ): MessageEncryption {
        return MessageEncryption(cryptoManager)
    }
    
    /**
     * Provides SecureStorageManager singleton.
     * 
     * SecureStorageManager handles secure storage of:
     * - Shared secrets
     * - Paired device information
     * - Device IDs
     * 
     * Uses Android's EncryptedSharedPreferences for hardware-backed
     * encryption on supported devices.
     * 
     * @param context Application context for storage access
     * @param cryptoManager CryptoManager instance for crypto operations
     * @return SecureStorageManager instance
     */
    @Provides
    @Singleton
    fun provideSecureStorageManager(
        @ApplicationContext context: Context,
        cryptoManager: CryptoManager
    ): SecureStorageManager {
        return SecureStorageManager(context, cryptoManager)
    }
}
