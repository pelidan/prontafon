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

//! Application state management.

use parking_lot::RwLock;
use std::sync::Arc;

/// Connection status.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionStatus {
    Disconnected,
    #[allow(dead_code)]
    Connecting,
    Connected,
    Error,
}

impl ConnectionStatus {
    pub fn icon_name(&self) -> &'static str {
        match self {
            ConnectionStatus::Connected => "prontafon-connected-symbolic",
            ConnectionStatus::Disconnected
            | ConnectionStatus::Connecting
            | ConnectionStatus::Error => "prontafon-disconnected-symbolic",
        }
    }
}

/// Shared application state.
#[derive(Debug)]
pub struct AppState {
    /// Current connection status.
    pub connection_status: RwLock<ConnectionStatus>,

    /// Connected device name.
    pub connected_device: RwLock<Option<String>>,

    /// Command being recorded (if in recording mode).
    pub recording_command: RwLock<Option<String>>,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            connection_status: RwLock::new(ConnectionStatus::Disconnected),
            connected_device: RwLock::new(None),
            recording_command: RwLock::new(None),
        }
    }
}

impl AppState {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn set_connected(&self, device_name: String) {
        *self.connection_status.write() = ConnectionStatus::Connected;
        *self.connected_device.write() = Some(device_name);
    }

    pub fn set_disconnected(&self) {
        *self.connection_status.write() = ConnectionStatus::Disconnected;
        *self.connected_device.write() = None;
    }

    pub fn set_error(&self) {
        *self.connection_status.write() = ConnectionStatus::Error;
    }

    pub fn get_status(&self) -> ConnectionStatus {
        *self.connection_status.read()
    }

    pub fn get_device_name(&self) -> Option<String> {
        self.connected_device.read().clone()
    }

    /// Start recording mode for a command.
    pub fn start_recording(&self, command: String) {
        *self.recording_command.write() = Some(command);
    }

    /// Stop recording mode and return the command that was being recorded.
    pub fn stop_recording(&self) -> Option<String> {
        self.recording_command.write().take()
    }

    /// Check if we're in recording mode.
    pub fn is_recording(&self) -> bool {
        self.recording_command.read().is_some()
    }

    /// Get the command being recorded.
    pub fn get_recording_command(&self) -> Option<String> {
        self.recording_command.read().clone()
    }
}
