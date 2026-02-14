// Copyright 2026 Daniel Pelikan
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! BLE service and characteristic UUIDs for Prontafon.

use uuid::Uuid;

/// Prontafon GATT service UUID.
pub const SERVICE_UUID: Uuid = Uuid::from_u128(0xa1b2c3d4_e5f6_7890_abcd_ef1234567890);

/// Command RX characteristic UUID (Android writes commands here).
/// Properties: Write, Write Without Response
pub const COMMAND_RX_UUID: Uuid = Uuid::from_u128(0xa1b2c3d4_e5f6_7890_abcd_ef1234567891);

/// Response TX characteristic UUID (Linux sends responses here).
/// Properties: Notify
pub const RESPONSE_TX_UUID: Uuid = Uuid::from_u128(0xa1b2c3d4_e5f6_7890_abcd_ef1234567892);

/// Status characteristic UUID (Connection and pairing status).
/// Properties: Read, Notify
pub const STATUS_UUID: Uuid = Uuid::from_u128(0xa1b2c3d4_e5f6_7890_abcd_ef1234567893);

/// MTU Info characteristic UUID (Current negotiated MTU).
/// Properties: Read
pub const MTU_INFO_UUID: Uuid = Uuid::from_u128(0xa1b2c3d4_e5f6_7890_abcd_ef1234567894);

/// BLE packet flags.
pub mod flags {
    pub const FIRST: u8 = 0x08; // First packet of message
    pub const LAST: u8 = 0x04; // Last packet of message
}

/// BLE status codes (for Status characteristic).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum StatusCode {
    Idle = 0x00,            // Not paired
    AwaitingPairing = 0x01, // Awaiting pairing
    Paired = 0x02,          // Paired and ready
}

impl StatusCode {
    pub fn as_bytes(&self) -> Vec<u8> {
        vec![*self as u8]
    }
}

/// BLE configuration constants.
pub mod config {
    /// Default MTU (minimum for all BLE devices).
    pub const DEFAULT_MTU: usize = 23;

    /// ATT protocol overhead (3 bytes).
    pub const ATT_OVERHEAD: usize = 3;

    /// Packet header size (4 bytes for first packet, 2 for continuation).
    pub const HEADER_SIZE_FIRST: usize = 4;
    pub const HEADER_SIZE_CONTINUATION: usize = 2;

    /// Calculate effective payload size for a given MTU.
    pub fn effective_payload_size(mtu: usize, is_first: bool) -> usize {
        mtu - ATT_OVERHEAD
            - if is_first {
                HEADER_SIZE_FIRST
            } else {
                HEADER_SIZE_CONTINUATION
            }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_uuid_format() {
        // Ensure UUIDs are correctly formatted
        assert_eq!(
            SERVICE_UUID.to_string().to_lowercase(),
            "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        );
        assert_eq!(
            COMMAND_RX_UUID.to_string().to_lowercase(),
            "a1b2c3d4-e5f6-7890-abcd-ef1234567891"
        );
    }

    #[test]
    fn test_effective_payload() {
        // With default MTU (23 bytes)
        assert_eq!(config::effective_payload_size(23, true), 16); // 23 - 3 - 4
        assert_eq!(config::effective_payload_size(23, false), 18); // 23 - 3 - 2

        // With target MTU (512 bytes)
        assert_eq!(config::effective_payload_size(512, true), 505); // 512 - 3 - 4
        assert_eq!(config::effective_payload_size(512, false), 507); // 512 - 3 - 2
    }
}
