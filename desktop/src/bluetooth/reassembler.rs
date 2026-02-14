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

//! BLE packet reassembly logic.

use tracing::{debug, warn};

use super::ble_constants::flags;

/// Handles reassembly of BLE packets into complete messages.
pub struct MessageReassembler {
    buffer: Vec<u8>,
    expected_length: usize,
    expected_seq: u8,
    in_progress: bool,
}

impl MessageReassembler {
    /// Create a new message reassembler.
    pub fn new() -> Self {
        Self {
            buffer: Vec::with_capacity(4096),
            expected_length: 0,
            expected_seq: 0,
            in_progress: false,
        }
    }

    /// Process an incoming BLE packet.
    ///
    /// Returns `Some(complete_message)` when a full message is reassembled,
    /// otherwise returns `None`.
    pub fn process_packet(&mut self, packet: &[u8]) -> Option<Vec<u8>> {
        if packet.len() < 2 {
            warn!("Packet too short: {} bytes", packet.len());
            return None;
        }

        let flags = packet[0];
        let seq = packet[1];
        let is_first = (flags & flags::FIRST) != 0;
        let is_last = (flags & flags::LAST) != 0;

        if is_first {
            // Start of new message
            if packet.len() < 4 {
                warn!("First packet too short: {} bytes", packet.len());
                return None;
            }

            self.buffer.clear();
            self.expected_length = u16::from_le_bytes([packet[2], packet[3]]) as usize;
            self.expected_seq = 0;
            self.in_progress = true;

            // Payload starts at byte 4 for first packet
            self.buffer.extend_from_slice(&packet[4..]);

            debug!(
                "Started message reassembly, expecting {} bytes",
                self.expected_length
            );
        } else if self.in_progress {
            // Continuation packet
            if seq != self.expected_seq {
                warn!(
                    "Sequence error: expected {}, got {}",
                    self.expected_seq, seq
                );
                self.reset();
                return None;
            }

            // Payload starts at byte 2 for continuation packets
            self.buffer.extend_from_slice(&packet[2..]);
        } else {
            // Received continuation without start
            warn!("Received continuation packet without start");
            return None;
        }

        self.expected_seq = self.expected_seq.wrapping_add(1);

        if is_last {
            self.in_progress = false;

            if self.buffer.len() == self.expected_length {
                debug!("Message reassembly complete: {} bytes", self.buffer.len());
                return Some(std::mem::take(&mut self.buffer));
            } else {
                warn!(
                    "Length mismatch: expected {}, got {}",
                    self.expected_length,
                    self.buffer.len()
                );
                self.reset();
            }
        }

        None
    }

    /// Reset the reassembler state.
    pub fn reset(&mut self) {
        self.buffer.clear();
        self.expected_length = 0;
        self.expected_seq = 0;
        self.in_progress = false;
    }

    /// Check if reassembly is in progress.
    #[allow(dead_code)]
    pub fn is_in_progress(&self) -> bool {
        self.in_progress
    }

    /// Get current buffer size.
    #[allow(dead_code)]
    pub fn buffer_size(&self) -> usize {
        self.buffer.len()
    }
}

impl Default for MessageReassembler {
    fn default() -> Self {
        Self::new()
    }
}

/// Helper function to chunk a message into BLE packets.
pub fn chunk_message(data: &[u8], mtu: usize) -> Vec<Vec<u8>> {
    use super::ble_constants::config;

    if data.is_empty() {
        return vec![];
    }

    let mut packets = Vec::new();
    let mut offset = 0;
    let mut seq = 0u8;

    while offset < data.len() {
        let is_first = offset == 0;
        let remaining = data.len() - offset;
        let effective_payload = config::effective_payload_size(mtu, is_first);
        let chunk_size = remaining.min(effective_payload);
        let is_last = offset + chunk_size >= data.len();

        let mut packet = Vec::new();

        // Flags byte
        let mut flags_byte = 0u8;
        if is_first {
            flags_byte |= flags::FIRST;
        }
        if is_last {
            flags_byte |= flags::LAST;
        }
        packet.push(flags_byte);

        // Sequence number
        packet.push(seq);
        seq = seq.wrapping_add(1);

        // Total length (only in first packet)
        if is_first {
            let len_bytes = (data.len() as u16).to_le_bytes();
            packet.push(len_bytes[0]);
            packet.push(len_bytes[1]);
        }

        // Payload chunk
        packet.extend_from_slice(&data[offset..offset + chunk_size]);

        packets.push(packet);
        offset += chunk_size;
    }

    packets
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_single_packet_message() {
        let mut reassembler = MessageReassembler::new();

        // Flags: FIRST | LAST (0x0C), Seq: 0, Length: 5
        let packet = vec![0x0C, 0x00, 0x05, 0x00, b'h', b'e', b'l', b'l', b'o'];

        let result = reassembler.process_packet(&packet);
        assert_eq!(result, Some(b"hello".to_vec()));
        assert!(!reassembler.is_in_progress());
    }

    #[test]
    fn test_multi_packet_message() {
        let mut reassembler = MessageReassembler::new();

        // First packet: 10 bytes total, 5 bytes in this packet
        let packet1 = vec![0x08, 0x00, 0x0A, 0x00, b'h', b'e', b'l', b'l', b'o'];
        assert!(reassembler.process_packet(&packet1).is_none());
        assert!(reassembler.is_in_progress());

        // Last packet: remaining 5 bytes
        let packet2 = vec![0x04, 0x01, b'w', b'o', b'r', b'l', b'd'];
        let result = reassembler.process_packet(&packet2);
        assert_eq!(result, Some(b"helloworld".to_vec()));
        assert!(!reassembler.is_in_progress());
    }

    #[test]
    fn test_three_packet_message() {
        let mut reassembler = MessageReassembler::new();

        // First packet
        let packet1 = vec![0x08, 0x00, 0x0F, 0x00, b'a', b'b', b'c', b'd', b'e'];
        assert!(reassembler.process_packet(&packet1).is_none());

        // Middle packet
        let packet2 = vec![0x00, 0x01, b'f', b'g', b'h', b'i', b'j'];
        assert!(reassembler.process_packet(&packet2).is_none());

        // Last packet
        let packet3 = vec![0x04, 0x02, b'k', b'l', b'm', b'n', b'o'];
        let result = reassembler.process_packet(&packet3);
        assert_eq!(result, Some(b"abcdefghijklmno".to_vec()));
    }

    #[test]
    fn test_sequence_error() {
        let mut reassembler = MessageReassembler::new();

        // First packet
        let packet1 = vec![0x08, 0x00, 0x0A, 0x00, b'h', b'e', b'l', b'l', b'o'];
        assert!(reassembler.process_packet(&packet1).is_none());

        // Wrong sequence (should be 1, but is 2)
        let packet2 = vec![0x04, 0x02, b'w', b'o', b'r', b'l', b'd'];
        let result = reassembler.process_packet(&packet2);
        assert!(result.is_none());
        assert!(!reassembler.is_in_progress());
    }

    #[test]
    fn test_length_mismatch() {
        let mut reassembler = MessageReassembler::new();

        // First packet says 10 bytes, but only provides 5 + 3 = 8
        let packet1 = vec![0x08, 0x00, 0x0A, 0x00, b'h', b'e', b'l', b'l', b'o'];
        assert!(reassembler.process_packet(&packet1).is_none());

        // Last packet with only 3 more bytes (total 8, expected 10)
        let packet2 = vec![0x04, 0x01, b'w', b'o', b'w'];
        let result = reassembler.process_packet(&packet2);
        assert!(result.is_none());
    }

    #[test]
    fn test_chunk_message_single_packet() {
        let data = b"hello";
        let mtu = 512;
        let packets = chunk_message(data, mtu);

        assert_eq!(packets.len(), 1);
        // Should be: [flags, seq, len_low, len_high, ...payload]
        assert_eq!(packets[0][0], 0x0C); // FIRST | LAST
        assert_eq!(packets[0][1], 0x00); // Seq 0
        assert_eq!(packets[0][2], 0x05); // Length low byte
        assert_eq!(packets[0][3], 0x00); // Length high byte
        assert_eq!(&packets[0][4..], b"hello");
    }

    #[test]
    fn test_chunk_message_multi_packet() {
        // Create data that will require multiple packets with small MTU
        let data = vec![b'A'; 100];
        let mtu = 23; // Default BLE MTU
        let packets = chunk_message(&data, mtu);

        // With MTU 23, effective payload for first packet is 16 bytes
        // Effective payload for continuation is 18 bytes
        // So: 16 + 18 + 18 + 18 + 18 + 12 = 100 (6 packets)
        assert!(packets.len() > 1);

        // First packet should have FIRST flag
        assert_eq!(packets[0][0] & flags::FIRST, flags::FIRST);
        assert_eq!(packets[0][1], 0); // Seq 0

        // Last packet should have LAST flag
        let last_idx = packets.len() - 1;
        assert_eq!(packets[last_idx][0] & flags::LAST, flags::LAST);
        assert_eq!(packets[last_idx][1], last_idx as u8); // Seq matches index
    }

    #[test]
    fn test_roundtrip() {
        let original_data = b"This is a test message that will be chunked and reassembled!";
        let mtu = 23;

        let packets = chunk_message(original_data, mtu);
        let mut reassembler = MessageReassembler::new();

        let mut result = None;
        for packet in packets {
            if let Some(msg) = reassembler.process_packet(&packet) {
                result = Some(msg);
            }
        }

        assert_eq!(result, Some(original_data.to_vec()));
    }
}
