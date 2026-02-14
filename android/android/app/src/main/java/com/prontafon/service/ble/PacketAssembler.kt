package com.prontafon.service.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles BLE packet chunking for outgoing messages.
 * 
 * BLE has MTU limitations (typically 23-512 bytes), so messages must be
 * chunked into multiple packets with proper framing.
 * 
 * Packet format:
 * - First packet: [Flags:1] [Seq:1] [TotalLen:2 LE] [Payload:N]
 * - Continuation: [Flags:1] [Seq:1] [Payload:N]
 */
object PacketAssembler {
    
    /**
     * Calculate effective payload size for a given MTU.
     * 
     * @param mtu The negotiated MTU size
     * @param isFirst True if this is the first packet (larger header)
     * @return Maximum payload bytes that fit in this packet
     */
    fun effectivePayloadSize(mtu: Int, isFirst: Boolean): Int {
        val headerSize = if (isFirst) {
            BleConstants.HEADER_SIZE_FIRST
        } else {
            BleConstants.HEADER_SIZE_CONTINUATION
        }
        return mtu - BleConstants.ATT_OVERHEAD - headerSize
    }
    
    /**
     * Chunk a message into BLE packets.
     * 
     * @param data The complete message bytes to send
     * @param mtu The negotiated MTU size
     * @return List of packets ready to send over BLE
     * @throws IllegalArgumentException if data is empty or MTU is too small
     */
    fun chunkMessage(data: ByteArray, mtu: Int): List<ByteArray> {
        require(data.isNotEmpty()) { "Cannot chunk empty data" }
        require(mtu >= BleConstants.MIN_MTU) { 
            "MTU must be at least ${BleConstants.MIN_MTU}, got $mtu" 
        }
        
        val packets = mutableListOf<ByteArray>()
        var offset = 0
        var seq = 0
        
        while (offset < data.size) {
            val isFirst = offset == 0
            val remaining = data.size - offset
            
            // Calculate payload size for this packet
            val effectivePayload = effectivePayloadSize(mtu, isFirst)
            require(effectivePayload > 0) {
                "MTU $mtu too small for packet headers"
            }
            
            val chunkSize = minOf(remaining, effectivePayload)
            val isLast = offset + chunkSize >= data.size
            
            // Build packet
            val headerSize = if (isFirst) {
                BleConstants.HEADER_SIZE_FIRST
            } else {
                BleConstants.HEADER_SIZE_CONTINUATION
            }
            
            val packet = ByteArray(headerSize + chunkSize)
            val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            
            // Flags byte
            var flags = 0
            if (isFirst) flags = flags or BlePacketFlags.FIRST
            if (isLast) flags = flags or BlePacketFlags.LAST
            buffer.put(flags.toByte())
            
            // Sequence number (wraps at 255)
            buffer.put((seq and 0xFF).toByte())
            seq++
            
            // Total length (only in first packet, little-endian)
            if (isFirst) {
                buffer.putShort(data.size.toShort())
            }
            
            // Payload chunk
            buffer.put(data, offset, chunkSize)
            
            packets.add(packet)
            offset += chunkSize
        }
        
        return packets
    }
    
    /**
     * Create a single packet for small messages that fit in one MTU.
     * 
     * This is an optimization for common case where message fits in one packet.
     * 
     * @param data The complete message bytes
     * @param mtu The negotiated MTU size
     * @return Single packet if message fits, null otherwise
     */
    fun createSinglePacket(data: ByteArray, mtu: Int): ByteArray? {
        val maxPayload = effectivePayloadSize(mtu, isFirst = true)
        if (data.size > maxPayload) return null
        
        val packet = ByteArray(BleConstants.HEADER_SIZE_FIRST + data.size)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        
        // Flags: FIRST | LAST (single packet message)
        buffer.put((BlePacketFlags.FIRST or BlePacketFlags.LAST).toByte())
        
        // Sequence: 0
        buffer.put(0.toByte())
        
        // Total length
        buffer.putShort(data.size.toShort())
        
        // Payload
        buffer.put(data)
        
        return packet
    }
}
