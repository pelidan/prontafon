package com.prontafon.service.ble

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * BLE service and characteristic UUIDs for Prontafon.
 */
object BleConstants {
    /** Prontafon GATT service UUID */
    const val SERVICE_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
    
    /** Command RX characteristic UUID (Android writes commands here) */
    const val COMMAND_RX_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567891"
    
    /** Response TX characteristic UUID (Linux sends responses here) */
    const val RESPONSE_TX_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567892"
    
    /** Status characteristic UUID (Connection and pairing status) */
    const val STATUS_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567893"
    
    /** MTU Info characteristic UUID (Current negotiated MTU) */
    const val MTU_INFO_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567894"
    
    /** Default MTU (minimum for all BLE devices) */
    const val DEFAULT_MTU = 23
    
    /** Target MTU to negotiate (512 bytes allows ~508 byte payloads) */
    const val TARGET_MTU = 512
    
    /** Minimum acceptable MTU */
    const val MIN_MTU = 23
    
    /** ATT protocol overhead (3 bytes) */
    const val ATT_OVERHEAD = 3
    
    /** Packet header size for first packet: [Flags:1][Seq:1][TotalLen:2] */
    const val HEADER_SIZE_FIRST = 4
    
    /** Packet header size for continuation packets: [Flags:1][Seq:1] */
    const val HEADER_SIZE_CONTINUATION = 2
    
    /** Scan timeout duration */
    val SCAN_TIMEOUT: Duration = 15.seconds
    
    /** Connection timeout */
    val CONNECTION_TIMEOUT: Duration = 15.seconds
    
    /** ACK timeout */
    val ACK_TIMEOUT: Duration = 1.seconds
    
    /** Heartbeat interval */
    val HEARTBEAT_INTERVAL: Duration = 5.seconds
    
    /** Maximum reconnection attempts */
    const val MAX_RECONNECT_ATTEMPTS = 5
    
    /** Minimum interval between sending messages (ms) */
    const val MIN_SEND_INTERVAL_MS = 50L
    
    /** Delay after service discovery before sending pairing request (ms) */
    const val PAIRING_REQUEST_DELAY_MS = 500L

    /** Delay between retries for word sending (ms) */
    const val RETRY_DELAY_MS = 50L
}

/**
 * BLE packet flags for message framing.
 * 
 * Flags are stored in the first byte of each packet:
 * - Bit 3 (0x08): FIRST - First packet of a message
 * - Bit 2 (0x04): LAST - Last packet of a message  
 * - Bit 1 (0x02): ACK_REQ - Request acknowledgment (reserved for future use)
 */
object BlePacketFlags {
    /** First packet of message */
    const val FIRST = 0x08
    
    /** Last packet of message */
    const val LAST = 0x04
    
    /** Request acknowledgment */
    const val ACK_REQ = 0x02
}

/**
 * BLE status codes for the Status characteristic.
 * 
 * The Status characteristic is read/notify and indicates the current
 * state of the Linux server:
 */
object BleStatusCode {
    /** Not paired, idle state */
    const val IDLE = 0x00
    
    /** Awaiting pairing PIN entry on Linux side */
    const val AWAITING_PAIRING = 0x01
    
    /** Paired and ready for communication */
    const val PAIRED = 0x02
    
    /** Currently processing a command */
    const val BUSY = 0x03
    
    /** Error state */
    const val ERROR = 0xFF
}
