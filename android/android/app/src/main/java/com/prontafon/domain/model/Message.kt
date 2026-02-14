package com.prontafon.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import com.prontafon.util.crypto.CryptoManager

/**
 * Protocol version for message compatibility
 */
const val PROTOCOL_VERSION = 3

/**
 * JSON configuration for message serialization
 */
val messageJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

/**
 * Message type enumeration with wire values
 */
@Serializable(with = MessageTypeSerializer::class)
enum class MessageType(val value: String) {
    TEXT("TEXT"),
    WORD("WORD"),  // Single word with sequence number
    COMMAND("COMMAND"),
    HEARTBEAT("HEARTBEAT"),
    ACK("ACK"),
    PAIR_REQ("PAIR_REQ"),
    PAIR_ACK("PAIR_ACK");

    companion object {
        fun fromValue(value: String): MessageType {
            return entries.find { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown message type: $value")
        }
    }
}

/**
 * Custom serializer for MessageType to use value field
 */
object MessageTypeSerializer : KSerializer<MessageType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("MessageType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MessageType) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): MessageType {
        return MessageType.fromValue(decoder.decodeString())
    }
}

/**
 * Payload for WORD messages - single word with session ID
 */
@Serializable
data class WordPayload(
    @SerialName("word")
    val word: String,
    @SerialName("session")
    val session: String,
    @SerialName("ts")
    val ts: Long = System.currentTimeMillis()
)

/**
 * Protocol message for BLE communication.
 * Matches Flutter's Message class exactly.
 */
@Serializable
data class Message(
    @SerialName("v")
    val version: Int = PROTOCOL_VERSION,

    @SerialName("t")
    val messageType: MessageType,

    @SerialName("p")
    var payload: String,

    @SerialName("ts")
    val timestamp: Long = System.currentTimeMillis(),

    @SerialName("cs")
    var checksum: String = ""
) {
    /**
     * Whether this message type should be encrypted
     */
    val shouldEncrypt: Boolean
        get() = when (messageType) {
            MessageType.PAIR_REQ,
            MessageType.PAIR_ACK,
            MessageType.ACK,
            MessageType.HEARTBEAT -> false
            else -> true
        }

    /**
     * Serialize message to JSON string.
     * 
     * NOTE: For messages requiring checksum verification (TEXT, COMMAND),
     * use signAndToJson(secret) to include cryptographic checksum.
     * Messages without a secret will have an empty checksum.
     */
    fun toJson(): String {
        return messageJson.encodeToString(serializer(), this)
    }
    
    /**
     * Sign the message with the shared secret and serialize to JSON.
     * Uses SHA-256 checksum compatible with desktop implementation.
     * 
     * @param secret The shared secret key (32 bytes)
     * @return JSON string with cryptographic checksum
     */
    fun signAndToJson(secret: ByteArray?): String {
        if (secret != null) {
            checksum = computeChecksum(secret)
        }
        return messageJson.encodeToString(serializer(), this)
    }

    /**
     * Compute SHA-256 checksum of message content.
     * Format: SHA256(version + msgType + payload + timestamp + secret)[0:4] as hex
     * This matches the desktop Rust implementation exactly.
     * 
     * @param secret The shared secret key (32 bytes)
     * @return 8-character hex string (first 4 bytes of SHA-256 hash)
     */
    fun computeChecksum(secret: ByteArray): String {
        return CryptoManager.calculateChecksum(
            version = version,
            msgType = messageType.value,
            payload = payload,
            timestamp = timestamp,
            secret = secret
        )
    }

    /**
     * Verify message checksum against a computed checksum.
     * 
     * @param secret The shared secret key (32 bytes)
     * @return true if checksum matches or is empty, false otherwise
     */
    fun verifyChecksum(secret: ByteArray): Boolean {
        if (checksum.isEmpty()) return true
        return checksum.equals(computeChecksum(secret), ignoreCase = true)
    }

    companion object {
        /**
         * Parse message from JSON string
         */
        fun fromJson(json: String): Message {
            return messageJson.decodeFromString(serializer(), json)
        }

        /**
         * Create a text message
         */
        fun text(content: String): Message {
            return Message(
                messageType = MessageType.TEXT,
                payload = content
            )
        }

        /**
         * Create a command message
         */
        fun command(cmd: String): Message {
            return Message(
                messageType = MessageType.COMMAND,
                payload = cmd
            )
        }

        /**
         * Create a heartbeat message
         */
        fun heartbeat(): Message {
            return Message(
                messageType = MessageType.HEARTBEAT,
                payload = ""
            )
        }

        /**
         * Create an acknowledgment message
         */
        fun ack(originalTimestamp: Long): Message {
            return Message(
                messageType = MessageType.ACK,
                payload = originalTimestamp.toString()
            )
        }

        /**
         * Create a pairing request message
         */
        fun pairRequest(payload: PairRequestPayload): Message {
            return Message(
                messageType = MessageType.PAIR_REQ,
                payload = messageJson.encodeToString(PairRequestPayload.serializer(), payload)
            )
        }

        /**
         * Create a pairing acknowledgment message
         */
        fun pairAck(payload: PairAckPayload): Message {
            return Message(
                messageType = MessageType.PAIR_ACK,
                payload = messageJson.encodeToString(PairAckPayload.serializer(), payload)
            )
        }

        /**
         * Create a word message with session ID
         */
        fun word(word: String, session: String): Message {
            val payload = WordPayload(word, session)
            return Message(
                messageType = MessageType.WORD,
                payload = messageJson.encodeToString(WordPayload.serializer(), payload)
            )
        }
    }
}

/**
 * Extension to parse payload as PairRequestPayload
 */
fun Message.parsePairRequestPayload(): PairRequestPayload? {
    if (messageType != MessageType.PAIR_REQ) return null
    return try {
        messageJson.decodeFromString(PairRequestPayload.serializer(), payload)
    } catch (e: Exception) {
        null
    }
}

/**
 * Extension to parse payload as PairAckPayload
 */
fun Message.parsePairAckPayload(): PairAckPayload? {
    if (messageType != MessageType.PAIR_ACK) return null
    return try {
        messageJson.decodeFromString(PairAckPayload.serializer(), payload)
    } catch (e: Exception) {
        null
    }
}
