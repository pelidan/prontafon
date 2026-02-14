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

//! Bluetooth communication module.
//!
//! Handles BLE GATT server for receiving messages from Android app.

// BLE modules
mod ble_constants;
mod gatt_server;
mod reassembler;

// Protocol (shared)
mod protocol;

// Export BLE components (only what's used externally)
pub use gatt_server::{ConnectionEvent, GattServer};

// Export protocol (only what's used externally)
pub use protocol::CommandCode;
