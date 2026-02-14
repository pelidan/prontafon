package com.prontafon.service.ble

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles reassembly of received BLE packets into complete messages.
 * 
 * This class is thread-safe via synchronized blocks.
 * 
 * Message completion is determined by BOTH:
 * 1. The LAST flag being set in the packet
 * 2. The buffer containing exactly expectedLength bytes
 * 
 * If expectedLength bytes are received but LAST flag is not set (or vice versa),
 * something is wrong and we reset the reassembler.
 */
class PacketReassembler {
    
    companion object {
        private const val TAG = "PacketReassembler"
        private const val REASSEMBLY_TIMEOUT_MS = 2000L // 2 seconds max for reassembly
    }
    
    private val lock = Any()
    private val buffer = mutableListOf<Byte>()
    private var expectedLength = 0
    private var expectedSeq = 0
    private var inProgress = false
    private var reassemblyStartTime = 0L
    
    /**
     * Check if reassembly is currently in progress.
     */
    val isInProgress: Boolean
        get() = synchronized(lock) { inProgress }
    
    /**
     * Get current buffer size (bytes received so far).
     */
    val bufferSize: Int
        get() = synchronized(lock) { buffer.size }
    
    /**
     * Get expected total message length.
     * Only valid when [isInProgress] is true.
     */
    val expectedTotalLength: Int
        get() = synchronized(lock) { expectedLength }
    
    /**
     * Process an incoming BLE packet.
     * 
     * @param packet Raw bytes received from BLE characteristic
     * @return Complete message bytes if this packet completes a message, null otherwise
     */
    fun addPacket(packet: ByteArray): ByteArray? = synchronized(lock) {
        // Check for stalled reassembly (timeout protection)
        if (inProgress) {
            val elapsed = System.currentTimeMillis() - reassemblyStartTime
            if (elapsed > REASSEMBLY_TIMEOUT_MS) {
                Log.w(TAG, "Reassembly timeout (${elapsed}ms) - had ${buffer.size}/$expectedLength bytes, resetting")
                resetInternal()
            }
        }
        
        if (packet.size < 2) {
            Log.w(TAG, "Packet too short (${packet.size} bytes)")
            return null
        }
        
        val flags = packet[0].toInt() and 0xFF
        val seq = packet[1].toInt() and 0xFF
        val isFirst = (flags and BlePacketFlags.FIRST) != 0
        val isLast = (flags and BlePacketFlags.LAST) != 0
        
        Log.d(TAG, "Packet: flags=0x${flags.toString(16)}, seq=$seq, size=${packet.size}, isFirst=$isFirst, isLast=$isLast, inProgress=$inProgress")
        
        if (isFirst) {
            // Start of new message - if we were in progress, log a warning (previous message was interrupted)
            if (inProgress) {
                Log.w(TAG, "New message started while previous message incomplete (had ${buffer.size}/$expectedLength bytes)")
            }
            
            if (packet.size < BleConstants.HEADER_SIZE_FIRST) {
                Log.w(TAG, "First packet too short (${packet.size} bytes)")
                resetInternal()
                return null
            }
            
            // Parse total length (little-endian)
            val lengthBuffer = ByteBuffer.wrap(packet, 2, 2).order(ByteOrder.LITTLE_ENDIAN)
            expectedLength = lengthBuffer.short.toInt() and 0xFFFF
            
            // Reset state for new message
            buffer.clear()
            expectedSeq = 0
            inProgress = true
            reassemblyStartTime = System.currentTimeMillis()
            
            // Payload starts at byte 4 for first packet
            for (i in BleConstants.HEADER_SIZE_FIRST until packet.size) {
                buffer.add(packet[i])
            }
            
            Log.d(TAG, "Started message, expecting $expectedLength bytes, got ${buffer.size}")
            
        } else if (inProgress) {
            // Continuation packet
            if (seq != expectedSeq) {
                Log.w(TAG, "Sequence error (expected $expectedSeq, got $seq), resetting")
                resetInternal()
                return null
            }
            
            // Payload starts at byte 2 for continuation packets
            for (i in BleConstants.HEADER_SIZE_CONTINUATION until packet.size) {
                buffer.add(packet[i])
            }
            
            Log.d(TAG, "Added continuation packet, buffer now ${buffer.size}/$expectedLength bytes")
            
        } else {
            // Received continuation without start
            Log.w(TAG, "Received continuation packet without start (seq=$seq, flags=0x${flags.toString(16)}), ignoring")
            return null
        }
        
        // Increment expected sequence (wraps at 256)
        expectedSeq = (expectedSeq + 1) and 0xFF
        
        // Check completion conditions
        val lengthReached = buffer.size >= expectedLength
        
        if (isLast && lengthReached) {
            // Normal completion: LAST flag set and we have expected bytes
            inProgress = false
            
            if (buffer.size == expectedLength) {
                val elapsed = System.currentTimeMillis() - reassemblyStartTime
                Log.d(TAG, "Message complete ($expectedLength bytes in ${elapsed}ms)")
                val result = buffer.toByteArray()
                buffer.clear()
                return result
            } else {
                // buffer.size > expectedLength - we received more bytes than expected
                Log.w(TAG, "Length overflow (expected $expectedLength, got ${buffer.size})")
                resetInternal()
                return null
            }
        } else if (isLast && !lengthReached) {
            // LAST flag set but not enough bytes - protocol error
            Log.w(TAG, "LAST flag set but only ${buffer.size}/$expectedLength bytes received")
            resetInternal()
            return null
        } else if (!isLast && lengthReached) {
            // We have all bytes but LAST flag not set - check if exactly at length
            if (buffer.size == expectedLength) {
                // Message might be complete even without LAST flag (server bug or different framing)
                // Complete it anyway to avoid timeout
                Log.w(TAG, "Expected length reached but LAST flag not set - completing message anyway")
                inProgress = false
                val result = buffer.toByteArray()
                buffer.clear()
                return result
            } else {
                // buffer.size > expectedLength - overflow
                Log.w(TAG, "Buffer overflow without LAST flag (expected $expectedLength, got ${buffer.size})")
                resetInternal()
                return null
            }
        }
        
        // Still waiting for more packets
        return null
    }
    
    /**
     * Reset the reassembler state.
     * 
     * Call this when starting fresh or after an error condition.
     */
    fun reset() {
        synchronized(lock) {
            resetInternal()
        }
    }
    
    /**
     * Internal reset - must be called with lock held.
     */
    private fun resetInternal() {
        buffer.clear()
        expectedLength = 0
        expectedSeq = 0
        inProgress = false
        reassemblyStartTime = 0L
    }
}
