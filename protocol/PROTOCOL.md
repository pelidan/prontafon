# Prontafon Communication Protocol v3

## Overview

This document defines the communication protocol between the Prontafon Android app and Linux desktop app over Bluetooth Low Energy (BLE) GATT.

## Transport Layer

- **Protocol**: Bluetooth Low Energy (BLE) GATT
- **Role**: Linux desktop acts as GATT server, Android acts as GATT client
- **Service UUID**: `12345678-1234-5678-1234-56789abcdef0`
- **Framing**: JSON messages with binary chunking for large payloads
- **Encoding**: UTF-8

### GATT Characteristics

| Characteristic | UUID | Properties | Description |
|----------------|------|------------|-------------|
| Write | `12345678-1234-5678-1234-56789abcdef1` | Write | Client writes messages here |
| Notify | `12345678-1234-5678-1234-56789abcdef2` | Notify | Server sends responses here |

### Message Chunking

BLE has limited MTU (typically 512 bytes). Large messages are chunked:

**Chunk Header** (3 bytes):
- Byte 0: Sequence number (0 = first chunk)
- Byte 1-2: Total message length (big-endian u16)

**Single Packet** (sequence = 0, length <= MTU - 3):
```
[0x00] [length_hi] [length_lo] [json_bytes...]
```

**Multi-Packet**:
```
Packet 1: [0x00] [total_len_hi] [total_len_lo] [json_part1...]
Packet 2: [0x01] [payload_part2...]
Packet N: [0xNN] [payload_partN...]
```

## Message Structure

All messages are JSON objects:

```json
{
  "v": 3,
  "t": "MESSAGE_TYPE",
  "p": "payload",
  "ts": 1234567890123,
  "cs": "abc12345"
}
```

### Field Descriptions

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `v` | integer | Yes | Protocol version (`3`) |
| `t` | string | Yes | Message type |
| `p` | string | Yes | Message payload (may be encrypted) |
| `ts` | integer | Yes | Unix timestamp in milliseconds |
| `cs` | string | Yes | HMAC checksum for integrity |

### Checksum Calculation

```
checksum = SHA256(v + t + p + ts + shared_secret).hex()[0:8]
```

## Message Types

### WORD

Single word from speech recognition. Sent word-by-word for real-time command matching.

```json
{
  "v": 3,
  "t": "WORD",
  "p": "{\"word\":\"hello\",\"session_id\":\"abc123\",\"seq\":5,\"is_final\":false}",
  "ts": 1706745600000,
  "cs": "a1b2c3d4"
}
```

**Payload** (JSON string):
- `word`: The recognized word
- `session_id`: Recognition session identifier (changes when speech restarts)
- `seq`: Sequence number within session
- `is_final`: Whether this is the final result for the utterance

**Receiver Action**: Buffer words, match voice commands, type text.

### TEXT

Complete text to type (legacy, still supported).

```json
{
  "v": 3,
  "t": "TEXT",
  "p": "Hello, this is dictated text",
  "ts": 1706745600000,
  "cs": "a1b2c3d4"
}
```

**Receiver Action**: Type the payload text at current cursor position.

### COMMAND

Explicit voice command.

```json
{
  "v": 3,
  "t": "COMMAND",
  "p": "ENTER",
  "ts": 1706745600000,
  "cs": "e5f6g7h8"
}
```

**Command Codes**:

| Code | Action | Keyboard Equivalent |
|------|--------|---------------------|
| `ENTER` | Press Enter/Return | Enter |
| `BACKSPACE` | Delete previous character | Backspace |
| `SELECT_ALL` | Select all text | Ctrl+A |
| `COPY` | Copy selection | Ctrl+C |
| `PASTE` | Paste clipboard | Ctrl+V |
| `CUT` | Cut selection | Ctrl+X |
| `CANCEL` | Discard pending input | (no action) |

### ACK

Acknowledgment of received message.

```json
{
  "v": 3,
  "t": "ACK",
  "p": "{\"status\":\"ok\",\"ref_ts\":1706745600000}",
  "ts": 1706745600100,
  "cs": "m3n4o5p6"
}
```

### PAIR_REQ

Pairing request from Android with ECDH public key.

```json
{
  "v": 3,
  "t": "PAIR_REQ",
  "p": "{\"device_id\":\"android-xxx\",\"device_name\":\"Pixel 7\",\"public_key\":\"base64...\"}",
  "ts": 1706745600000,
  "cs": "q7r8s9t0"
}
```

**Payload**:
- `device_id`: Unique Android device identifier
- `device_name`: Human-readable device name
- `public_key`: X25519 public key (base64, 44 chars)

### PAIR_ACK

Pairing response from Linux with ECDH public key.

```json
{
  "v": 3,
  "t": "PAIR_ACK",
  "p": "{\"device_id\":\"linux-xxx\",\"public_key\":\"base64...\",\"status\":\"ok\",\"protocol_version\":3}",
  "ts": 1706745600000,
  "cs": "u1v2w3x4"
}
```

**Payload**:
- `device_id`: Unique Linux device identifier
- `public_key`: X25519 public key (base64, 44 chars)
- `status`: `"ok"` or `"error"`
- `protocol_version`: Supported protocol version
- `error` (optional): Error message if status is "error"

## Encryption

### Key Exchange (ECDH)

1. Android generates X25519 keypair, sends public key in `PAIR_REQ`
2. Linux generates X25519 keypair, computes shared secret
3. Linux sends its public key in `PAIR_ACK`
4. Android computes same shared secret
5. Both derive encryption key:
   ```
   key = PBKDF2(
     password = hex(shared_secret) + android_device_id + linux_device_id,
     salt = "prontafon_v1",
     iterations = 100000,
     key_length = 32
   )
   ```

### Message Encryption (AES-256-GCM)

After pairing, message payloads are encrypted:

1. Generate random 12-byte nonce
2. Encrypt payload with AES-256-GCM
3. Base64-encode: `base64(nonce || ciphertext || tag)`

**Encrypted payload format**:
```json
{
  "v": 3,
  "t": "WORD",
  "p": "base64(nonce + AES-GCM(actual_payload))",
  "ts": 1706745600000,
  "cs": "checksum_of_encrypted_payload"
}
```

## Connection Flow

### Initial Pairing

```
Android (GATT Client)                    Linux (GATT Server)
    |                                           |
    |  [Discover service, connect]              |
    |------------------------------------------>|
    |                                           |
    |  PAIR_REQ (device_id, public_key)         |
    |  [Write to Write Characteristic]          |
    |------------------------------------------>|
    |                                           |
    |                    [User accepts on Linux]|
    |                                           |
    |               PAIR_ACK (public_key, ok)   |
    |               [Notify via Notify Char]    |
    |<------------------------------------------|
    |                                           |
    [Both compute shared secret, derive key]    |
    |                                           |
    |  WORD/TEXT (encrypted)                    |
    |------------------------------------------>|
    |                           ACK             |
    |<------------------------------------------|
```

### Reconnection (Previously Paired)

```
Android                                  Linux
    |                                      |
    |  [Connect to known device]           |
    |------------------------------------->|
    |                                      |
    |  PAIR_REQ (same device_id)           |
    |------------------------------------->|
    |                                      |
    |         PAIR_ACK (auto-accepted)     |
    |<-------------------------------------|
    |                                      |
    [Resume with existing shared secret]   |
```

## Voice Command Matching

The desktop app matches words to commands using configurable phrases:

**Default phrases** (`~/.config/prontafon/voice_commands.json`):
```json
{
  "version": 1,
  "mappings": [
    {"phrase": "enter", "command": "ENTER", "created_at": "2024-01-01T00:00:00Z"},
    {"phrase": "select all", "command": "SELECT_ALL", "created_at": "2024-01-01T00:00:00Z"},
    {"phrase": "copy", "command": "COPY", "created_at": "2024-01-01T00:00:00Z"},
    {"phrase": "paste", "command": "PASTE", "created_at": "2024-01-01T00:00:00Z"},
    {"phrase": "cut", "command": "CUT", "created_at": "2024-01-01T00:00:00Z"},
    {"phrase": "cancel", "command": "CANCEL", "created_at": "2024-01-01T00:00:00Z"}
  ]
}
```

**Matching algorithm**:
1. Buffer incoming words
2. For single-word commands: immediate match
3. For two-word commands: buffer first word, wait for second
4. If second word doesn't match: flush first word as text
5. Timeout (300ms): flush buffered words as text

## Error Handling

### Invalid Checksum
- Log error, don't process message
- Don't send ACK
- Continue listening

### Decryption Failure
- Log error
- May indicate key mismatch
- Request re-pairing if persistent

### Unknown Message Type
- Log warning
- Send ACK with error status
- Continue processing

## Version History

| Version | Changes |
|---------|---------|
| 1 | Initial RFCOMM-based protocol (deprecated) |
| 2 | BLE GATT transport, PIN-based pairing (deprecated) |
| 3 | ECDH key exchange, WORD message type, chunked messages |
