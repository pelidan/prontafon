package com.prontafon.service.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.util.Log
import com.prontafon.domain.model.*
import com.prontafon.util.crypto.CryptoManager
import com.prontafon.util.crypto.SecureStorageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import com.prontafon.util.crypto.EcdhManager
import java.security.KeyPair

/**
 * High-level BLE manager integrating all BLE components.
 * 
 * Features:
 * - Device scanning
 * - Connection management
 * - Message sending/receiving with encryption
 * - Pairing flow
 * - Heartbeat monitoring
 * - Automatic reconnection
 */
@Singleton
class BleManager @Inject constructor(
    private val scanner: BleScanner,
    private val connection: BleConnection,
    private val characteristicHandler: BleCharacteristicHandler,
    private val cryptoManager: CryptoManager,
    private val ecdhManager: EcdhManager,
    private val secureStorage: SecureStorageManager
) {
    companion object {
        private const val TAG = "BleManager"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Packet reassembly
    private val packetReassembler = PacketReassembler()
    
    // Crypto state
    private var sharedSecret: ByteArray? = null
    private var deviceId: String? = null
    private var linuxDeviceId: String? = null
    private var ecdhKeyPair: KeyPair? = null
    
    // Message sending throttle
    private var lastSendTime = 0L
    private val minSendInterval = BleConstants.MIN_SEND_INTERVAL_MS
    
    // State flows
    val connectionState: StateFlow<BtConnectionState> = connection.connectionState
    val scannedDevices: StateFlow<List<BleDeviceInfo>> = scanner.scannedDevices
    val isScanning: StateFlow<Boolean> = scanner.isScanning
    
    private val _connectedDevice = MutableStateFlow<BleDeviceInfo?>(null)
    val connectedDevice: StateFlow<BleDeviceInfo?> = _connectedDevice.asStateFlow()
    
    private val _receivedMessages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val receivedMessages: SharedFlow<Message> = _receivedMessages.asSharedFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Pending operations
    private val pendingMessages = mutableListOf<Message>()
    private val ackWaiters = mutableMapOf<Long, CompletableDeferred<Boolean>>()
    
    // Heartbeat
    private var heartbeatJob: Job? = null
    
    init {
        setupConnectionCallbacks()
        setupDataFlows()
    }
    
    /**
     * Check if Bluetooth is enabled.
     */
    fun isBluetoothEnabled(): Boolean = scanner.isBluetoothEnabled()
    
    /**
     * Start scanning for Prontafon devices.
     */
    fun startScan(timeoutMs: Long = BleConstants.SCAN_TIMEOUT.inWholeMilliseconds): Boolean {
        return scanner.startScan(timeoutMs)
    }
    
    /**
     * Stop scanning.
     */
    fun stopScan() {
        scanner.stopScan()
    }
    
    /**
     * Connect to a device.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(deviceInfo: BleDeviceInfo): Boolean {
        Log.d(TAG, "Connecting to ${deviceInfo.displayName}...")
        
        stopScan()
        _connectedDevice.value = deviceInfo
        _error.value = null
        
        // Load existing pairing if available
        loadPairing(deviceInfo.address)
        
        val success = connection.connect(deviceInfo.device)
        if (!success) {
            _error.value = "Failed to initiate connection"
            _connectedDevice.value = null
        }
        
        return success
    }
    
    /**
     * Disconnect from current device.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        heartbeatJob?.cancel()
        heartbeatJob = null
        connection.disconnect()
        _connectedDevice.value = null
        sharedSecret = null
        linuxDeviceId = null
        ecdhKeyPair = null  // Clear any pending pairing state
    }
    
    /**
     * Send a message to the connected device.
     */
    suspend fun sendMessage(message: Message): Boolean {
        // Allow PAIR_REQ to be sent during PAIRING state
        val canSend = connectionState.value == BtConnectionState.CONNECTED ||
                     (connectionState.value == BtConnectionState.PAIRING && 
                      message.messageType == MessageType.PAIR_REQ)
        
        if (!canSend) {
            Log.d(TAG, "Cannot send ${message.messageType} in state ${connectionState.value}, queueing message")
            pendingMessages.add(message)
            return false
        }
        
        // Throttle to prevent sending too quickly (skip for ACK and HEARTBEAT)
        if (message.messageType != MessageType.ACK && 
            message.messageType != MessageType.HEARTBEAT) {
            val now = System.currentTimeMillis()
            val timeSinceLastSend = now - lastSendTime
            if (timeSinceLastSend < minSendInterval) {
                val delayNeeded = minSendInterval - timeSinceLastSend
                Log.d(TAG, "Throttling: waiting ${delayNeeded}ms before send")
                kotlinx.coroutines.delay(delayNeeded)
            }
            lastSendTime = System.currentTimeMillis()
        }
        
        val gatt = connection.getGatt()
        if (gatt == null) {
            Log.e(TAG, "GATT not available")
            return false
        }
        
        return try {
            // Encrypt if we have shared secret and message should be encrypted
            val messageToSend = if (sharedSecret != null && message.shouldEncrypt) {
                encryptMessage(message)
            } else {
                message
            }
            
            // Sign with shared secret for checksum (required for desktop verification)
            val json = messageToSend.signAndToJson(sharedSecret)
            Log.d(TAG, "Sending: ${message.messageType} (${json.length} bytes)")
            
            val jsonBytes = json.toByteArray(Charsets.UTF_8)
            val mtu = connection.mtu.value
            
            // Write using characteristic handler
            val success = characteristicHandler.writeCommand(gatt, jsonBytes, mtu)
            
            if (success) {
                // Wait for ACK if needed
                if (message.messageType != MessageType.ACK && 
                    message.messageType != MessageType.HEARTBEAT) {
                    waitForAck(message.timestamp)
                } else {
                    true
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}", e)
            false
        }
    }
    

    
    /**
     * Get or create device ID.
     */
    suspend fun getOrCreateDeviceId(): String {
        if (deviceId == null) {
            val result = secureStorage.getDeviceId()
            deviceId = result.getOrNull() ?: cryptoManager.generateDeviceId()
        }
        return deviceId!!
    }
    
    /**
     * Clear crypto context (for fresh pairing).
     */
    fun clearCryptoContext() {
        sharedSecret?.let { cryptoManager.wipeBytes(it) }
        sharedSecret = null
        linuxDeviceId = null
        ecdhKeyPair = null
        Log.d(TAG, "Crypto context cleared")
    }
    
    // --- Private methods ---
    
    private fun setupConnectionCallbacks() {
        connection.onServicesDiscovered = { gatt ->
            onServicesDiscovered(gatt)
        }
        
        connection.onCharacteristicChanged = { characteristic, value ->
            characteristicHandler.handleCharacteristicChange(characteristic, value)
        }
        
        connection.onDisconnected = {
            heartbeatJob?.cancel()
            packetReassembler.reset()
        }
    }
    
    private fun setupDataFlows() {
        // Handle received data packets
        scope.launch {
            characteristicHandler.receivedData.collect { packet ->
                handleResponsePacket(packet)
            }
        }
        
        // Handle status updates
        scope.launch {
            characteristicHandler.statusUpdates.collect { status ->
                handleStatusUpdate(status)
            }
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun onServicesDiscovered(gatt: BluetoothGatt) {
        Log.d(TAG, "Setting up characteristics...")
        
        // Enable notifications in coroutine scope (now suspend functions with retry logic)
        scope.launch {
            val responseTxSuccess = characteristicHandler.enableResponseNotifications(gatt)
            if (!responseTxSuccess) {
                Log.e(TAG, "Failed to enable Response TX notifications after retries")
            }
            
            val statusSuccess = characteristicHandler.enableStatusNotifications(gatt)
            if (!statusSuccess) {
                Log.e(TAG, "Failed to enable Status notifications after retries")
            }
            
            // Start heartbeat
            startHeartbeat()
            
            // Send pairing request - give time for notifications to be set up
            delay(BleConstants.PAIRING_REQUEST_DELAY_MS)
            sendPairingRequest()
        }
    }
    
    private fun handleResponsePacket(packet: ByteArray) {
        Log.d(TAG, "Processing response packet: ${packet.size} bytes")
        val completeMessage = packetReassembler.addPacket(packet)
        
        if (completeMessage != null) {
            try {
                val json = String(completeMessage, Charsets.UTF_8)
                Log.d(TAG, "Complete message JSON: ${json.take(200)}...")
                val message = Message.fromJson(json)
                handleReceivedMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "Parse error: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "Packet added to reassembler, waiting for more data")
        }
    }
    
    private fun handleReceivedMessage(message: Message) {
        Log.d(TAG, "Received: ${message.messageType}")
        
        // Decrypt if encrypted
        val decryptedMessage = if (sharedSecret != null && message.shouldEncrypt) {
            decryptMessage(message) ?: return
        } else {
            message
        }
        
        when (decryptedMessage.messageType) {
            MessageType.ACK -> handleAck(decryptedMessage)
            MessageType.PAIR_ACK -> handlePairAck(decryptedMessage)
            MessageType.HEARTBEAT -> {
                // Respond with ACK
                scope.launch {
                    sendMessage(Message.ack(decryptedMessage.timestamp))
                }
            }
            else -> {
                // Emit to subscribers
                scope.launch {
                    _receivedMessages.emit(decryptedMessage)
                }
            }
        }
    }
    
    private fun handleStatusUpdate(status: ByteArray) {
        if (status.isEmpty()) return
        
        val statusCode = status[0].toInt() and 0xFF
        Log.d(TAG, "Status changed: 0x${statusCode.toString(16)}")
        
        when (statusCode) {
            BleStatusCode.AWAITING_PAIRING -> {
                Log.d(TAG, "Device awaiting pairing - showing PIN dialog")
                // Update connection state to trigger PIN dialog in UI
                connection.setAwaitingPairing()
            }
            BleStatusCode.PAIRED -> {
                // NOTE: We ignore PAIRED status notifications to prevent race conditions.
                // The desktop may send PAIRED status before the user enters their PIN on Android.
                // Pairing completion is ONLY handled via PAIR_ACK message in handlePairAck(),
                // which ensures PIN was actually verified.
                Log.d(TAG, "Received PAIRED status (ignored - waiting for PAIR_ACK message)")
            }
            BleStatusCode.ERROR -> {
                _error.value = "Device reported error"
            }
        }
    }
    
    private suspend fun sendPairingRequest() {
        val myDeviceId = getOrCreateDeviceId()
        Log.d(TAG, "Initiating ECDH pairing request from device: $myDeviceId")
        
        // Generate ECDH keypair
        val keypairResult = ecdhManager.generateKeyPair()
        if (keypairResult.isFailure) {
            Log.e(TAG, "Failed to generate ECDH keypair: ${keypairResult.exceptionOrNull()}")
            _error.value = "Failed to generate encryption keys"
            return
        }
        ecdhKeyPair = keypairResult.getOrThrow()
        Log.d(TAG, "ECDH keypair generated successfully")
        
        // Get public key as base64
        val publicKeyResult = ecdhManager.getPublicKeyBase64(ecdhKeyPair!!)
        if (publicKeyResult.isFailure) {
            Log.e(TAG, "Failed to extract public key: ${publicKeyResult.exceptionOrNull()}")
            _error.value = "Failed to extract encryption key"
            ecdhKeyPair = null
            return
        }
        val publicKey = publicKeyResult.getOrThrow()
        Log.d(TAG, "Public key extracted (${publicKey.length} chars)")
        
        val payload = PairRequestPayload.create(myDeviceId, "Android Device", publicKey)
        val message = Message.pairRequest(payload)
        
        Log.d(TAG, "Sending PAIR_REQ message (state: ${connectionState.value})")
        val success = sendMessage(message)
        
        if (success) {
            Log.d(TAG, "PAIR_REQ sent successfully, awaiting desktop confirmation")
        } else {
            // NOTE: Don't clear ecdhKeyPair here!
            // ACK timeout can happen if desktop's notification session is broken,
            // but desktop may still show the confirmation dialog and send PAIR_ACK.
            // The keypair will be cleared when:
            // - PAIR_ACK is received and handled (success or failure)
            // - Connection is disconnected/reset
            // - A new pairing request is initiated
            Log.w(TAG, "PAIR_REQ ACK timeout - desktop may still show confirmation dialog")
        }
        // No PIN dialog - desktop will show yes/no confirmation
    }
    
    private fun handlePairAck(message: Message) {
        Log.d(TAG, "Handling PAIR_ACK message")
        val payload = message.parsePairAckPayload()
        if (payload == null) {
            Log.e(TAG, "Invalid PAIR_ACK payload: ${message.payload}")
            return
        }
        
        Log.d(TAG, "PAIR_ACK: deviceId=${payload.deviceId}, isSuccess=${payload.isSuccess}")
        
        if (payload.isSuccess) {
            val keypair = ecdhKeyPair
            val desktopPublicKey = payload.publicKey
            
            if (keypair == null) {
                Log.e(TAG, "No ECDH keypair available")
                _error.value = "Pairing state error"
                connection.failPairing()
                return
            }
            
            if (desktopPublicKey.isNullOrEmpty()) {
                Log.e(TAG, "PAIR_ACK missing desktop public key")
                _error.value = "Invalid pairing response"
                connection.failPairing()
                return
            }
            
            linuxDeviceId = payload.deviceId
            Log.d(TAG, "Computing ECDH shared secret...")
            
            scope.launch {
                // Compute ECDH shared secret
                val sharedSecretResult = ecdhManager.computeSharedSecret(keypair, desktopPublicKey)
                if (sharedSecretResult.isFailure) {
                    Log.e(TAG, "Failed to compute shared secret: ${sharedSecretResult.exceptionOrNull()}")
                    _error.value = "Failed to establish secure connection"
                    ecdhKeyPair = null
                    connection.failPairing()
                    return@launch
                }
                val ecdhSharedSecret = sharedSecretResult.getOrThrow()
                
                // Derive AES key from ECDH shared secret
                val myDeviceId = getOrCreateDeviceId()
                val keyResult = cryptoManager.deriveKeyFromEcdh(ecdhSharedSecret, myDeviceId, payload.deviceId)
                
                keyResult.onSuccess { key ->
                    Log.d(TAG, "BleManager - Derived key (first 8 bytes): ${key.take(8).joinToString("") { "%02x".format(it) }}")
                    sharedSecret = key
                    ecdhKeyPair = null
                    Log.d(TAG, "Shared secret derived via ECDH")
                    
                    _connectedDevice.value?.let { device ->
                        storePairing(device.address, payload.deviceId, key)
                    }
                    
                    connection.completePairing()
                    sendPendingMessages()
                }.onFailure { e ->
                    Log.e(TAG, "Failed to derive key: ${e.message}")
                    _error.value = "Failed to establish secure connection"
                    ecdhKeyPair = null
                    connection.failPairing()
                }
            }
        } else {
            Log.e(TAG, "Pairing rejected: ${payload.error}")
            _error.value = payload.error ?: "Connection rejected"
            ecdhKeyPair = null
            connection.failPairing()
        }
    }
    
    private fun handleAck(message: Message) {
        val timestamp = message.payload.toLongOrNull() ?: return
        ackWaiters[timestamp]?.complete(true)
        ackWaiters.remove(timestamp)
    }
    
    private suspend fun waitForAck(timestamp: Long): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        ackWaiters[timestamp] = deferred
        
        return try {
            withTimeout(BleConstants.ACK_TIMEOUT.inWholeMilliseconds) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "ACK timeout for $timestamp (${BleConstants.ACK_TIMEOUT.inWholeMilliseconds}ms) - message may have been lost or desktop failed to send ACK")
            ackWaiters.remove(timestamp)
            false
        }
    }
    
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && connectionState.value == BtConnectionState.CONNECTED) {
                delay(BleConstants.HEARTBEAT_INTERVAL.inWholeMilliseconds)
                
                if (connectionState.value == BtConnectionState.CONNECTED) {
                    val success = sendMessage(Message.heartbeat())
                    if (!success) {
                        Log.w(TAG, "Heartbeat failed")
                    }
                }
            }
        }
    }
    
    private fun sendPendingMessages() {
        scope.launch {
            while (pendingMessages.isNotEmpty() && 
                   connectionState.value == BtConnectionState.CONNECTED) {
                val message = pendingMessages.removeAt(0)
                sendMessage(message)
            }
        }
    }
    
    private fun encryptMessage(message: Message): Message {
        val secret = sharedSecret ?: return message
        
        val result = cryptoManager.encrypt(message.payload, secret)
        return result.fold(
            onSuccess = { encrypted ->
                message.copy(payload = encrypted)
            },
            onFailure = { e ->
                Log.e(TAG, "Encryption failed: ${e.message}")
                message
            }
        )
    }
    
    private fun decryptMessage(message: Message): Message? {
        val secret = sharedSecret ?: return message
        
        val result = cryptoManager.decrypt(message.payload, secret)
        return result.fold(
            onSuccess = { decrypted ->
                message.copy(payload = decrypted)
            },
            onFailure = { e ->
                Log.e(TAG, "Decryption failed: ${e.message}")
                null
            }
        )
    }
    
    private suspend fun loadPairing(address: String) {
        val secretResult = secureStorage.getSharedSecret(address)
        secretResult.onSuccess { secret ->
            if (secret != null) {
                try {
                    sharedSecret = android.util.Base64.decode(secret, android.util.Base64.NO_WRAP)
                    Log.d(TAG, "Loaded existing pairing for $address")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode shared secret", e)
                }
            }
        }
    }
    
    private suspend fun storePairing(address: String, linuxId: String, secret: ByteArray) {
        val secretBase64 = android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP)
        val result = secureStorage.storeSharedSecret(address, secretBase64)
        result.onSuccess {
            Log.d(TAG, "Pairing stored for $address")
        }.onFailure { e ->
            Log.e(TAG, "Failed to store pairing: ${e.message}")
        }
    }
    
    /**
     * Release all resources.
     */
    fun release() {
        scope.cancel()
        stopScan()
        disconnect()
        sharedSecret?.let { cryptoManager.wipeBytes(it) }
        sharedSecret = null
    }
}
