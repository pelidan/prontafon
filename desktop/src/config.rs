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

//! Configuration module.
//!
//! Handles loading and saving application settings.

use anyhow::Result;
use gethostname::gethostname;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

/// Get a sanitized hostname suitable for Bluetooth device name.
/// Bluetooth names should only contain alphanumeric chars, spaces, and hyphens.
fn get_sanitized_hostname() -> String {
    let hostname = gethostname().to_string_lossy().to_string();
    // Sanitize: keep only alphanumeric, spaces, and hyphens
    let sanitized: String = hostname
        .chars()
        .map(|c| {
            if c.is_alphanumeric() || c == '-' || c == ' ' {
                c
            } else {
                '-'
            }
        })
        .collect();
    // Trim leading/trailing hyphens and collapse multiple hyphens
    let trimmed = sanitized.trim_matches('-');
    if trimmed.is_empty() {
        "Desktop".to_string()
    } else {
        trimmed.to_string()
    }
}

/// Application configuration.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    /// Data directory for storage and settings.
    #[serde(skip)]
    pub data_dir: PathBuf,

    /// Bluetooth settings.
    pub bluetooth: BluetoothConfig,

    /// Input settings.
    pub input: InputConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct BluetoothConfig {
    /// Device name advertised over Bluetooth.
    /// This is always computed at runtime from the system hostname.
    #[serde(skip)]
    pub device_name: String,

    /// Auto-accept connections from paired devices.
    pub auto_accept: bool,
}

impl Default for BluetoothConfig {
    fn default() -> Self {
        Self {
            device_name: get_sanitized_hostname(),
            auto_accept: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InputConfig {
    /// Delay between keystrokes in milliseconds.
    pub typing_delay_ms: u32,

    /// Preferred backend: "auto", "x11", or "wayland".
    pub prefer_backend: String,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            data_dir: dirs::data_dir()
                .unwrap_or_else(|| PathBuf::from("."))
                .join("prontafon"),
            bluetooth: BluetoothConfig::default(),
            input: InputConfig {
                typing_delay_ms: 10,
                prefer_backend: "auto".to_string(),
            },
        }
    }
}

impl Config {
    /// Load configuration from file or create default.
    pub fn load() -> Result<Self> {
        let config_dir = dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("prontafon");

        std::fs::create_dir_all(&config_dir)?;

        let config_path = config_dir.join("config.toml");

        let mut config = if config_path.exists() {
            let content = std::fs::read_to_string(&config_path)?;
            toml::from_str(&content)?
        } else {
            let config = Self::default();
            let content = toml::to_string_pretty(&config)?;
            std::fs::write(&config_path, content)?;
            config
        };

        // Set data directory
        config.data_dir = dirs::data_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("prontafon");
        std::fs::create_dir_all(&config.data_dir)?;

        Ok(config)
    }

    /// Save configuration to file.
    #[allow(dead_code)]
    pub fn save(&self) -> Result<()> {
        let config_dir = dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("prontafon");

        let config_path = config_dir.join("config.toml");
        let content = toml::to_string_pretty(self)?;
        std::fs::write(config_path, content)?;

        Ok(())
    }
}
