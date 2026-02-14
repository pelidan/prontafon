package com.prontafon.di.modules

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.prontafon.service.ble.*
import com.prontafon.util.crypto.CryptoManager
import com.prontafon.util.crypto.EcdhManager
import com.prontafon.util.crypto.SecureStorageManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing BLE infrastructure dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object BleModule {
    
    /**
     * Provide BluetoothManager system service.
     */
    @Provides
    @Singleton
    fun provideBluetoothManager(
        @ApplicationContext context: Context
    ): BluetoothManager {
        return context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    
    /**
     * Provide BluetoothAdapter.
     */
    @Provides
    @Singleton
    fun provideBluetoothAdapter(
        bluetoothManager: BluetoothManager
    ): BluetoothAdapter? {
        return bluetoothManager.adapter
    }
    
    /**
     * Provide BleScanner.
     */
    @Provides
    @Singleton
    fun provideBleScanner(
        @ApplicationContext context: Context
    ): BleScanner {
        return BleScanner(context)
    }
    
    /**
     * Provide BleConnection.
     */
    @Provides
    @Singleton
    fun provideBleConnection(
        @ApplicationContext context: Context
    ): BleConnection {
        return BleConnection(context)
    }
    
    /**
     * Provide BleCharacteristicHandler.
     */
    @Provides
    @Singleton
    fun provideBleCharacteristicHandler(): BleCharacteristicHandler {
        return BleCharacteristicHandler()
    }
    
    /**
     * Provide BleManager.
     * 
     * This is the main high-level API for BLE operations.
     */
    @Provides
    @Singleton
    fun provideBleManager(
        scanner: BleScanner,
        connection: BleConnection,
        characteristicHandler: BleCharacteristicHandler,
        cryptoManager: CryptoManager,
        ecdhManager: EcdhManager,
        secureStorage: SecureStorageManager
    ): BleManager {
        return BleManager(
            scanner = scanner,
            connection = connection,
            characteristicHandler = characteristicHandler,
            cryptoManager = cryptoManager,
            ecdhManager = ecdhManager,
            secureStorage = secureStorage
        )
    }
}
