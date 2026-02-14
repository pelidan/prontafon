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

//! Trusted device storage for auto-accept pairing.
//!
//! Handles storing and loading trusted device IDs that should be
//! automatically accepted when pairing is requested.

use anyhow::{Context, Result};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use tracing::{debug, info};

/// A trusted device that can auto-pair.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrustedDevice {
    /// Unique device identifier.
    pub device_id: String,
    /// Optional human-readable device name.
    pub device_name: Option<String>,
    /// ISO 8601 timestamp when first paired.
    pub first_paired: String,
    /// ISO 8601 timestamp when last connected.
    pub last_connected: String,
}

/// Trusted devices file format.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct TrustedDevicesFile {
    /// File format version.
    version: u32,
    /// List of trusted devices.
    devices: Vec<TrustedDevice>,
}

impl Default for TrustedDevicesFile {
    fn default() -> Self {
        Self {
            version: 1,
            devices: Vec::new(),
        }
    }
}

/// Store for managing trusted devices.
pub struct TrustedDeviceStore {
    file_path: PathBuf,
    devices: Vec<TrustedDevice>,
}

impl TrustedDeviceStore {
    /// Create a new trusted device store.
    ///
    /// # Arguments
    /// * `data_dir` - Directory where paired_devices.json will be stored
    pub fn new(data_dir: &Path) -> Result<Self> {
        let file_path = data_dir.join("paired_devices.json");

        // Ensure data directory exists
        std::fs::create_dir_all(data_dir)
            .with_context(|| format!("Failed to create data directory {:?}", data_dir))?;

        let devices = Self::load(&file_path)?;

        info!(
            "Loaded {} trusted device(s) from {:?}",
            devices.len(),
            file_path
        );

        Ok(Self { file_path, devices })
    }

    /// Check if a device ID is trusted.
    pub fn is_trusted(&self, device_id: &str) -> bool {
        self.devices.iter().any(|d| d.device_id == device_id)
    }

    /// Add a new trusted device.
    ///
    /// # Arguments
    /// * `device_id` - Unique device identifier
    /// * `device_name` - Optional human-readable device name
    pub fn add_trusted(&mut self, device_id: String, device_name: Option<String>) -> Result<()> {
        let now = Utc::now().to_rfc3339();

        // Check if device already exists
        if let Some(device) = self.devices.iter_mut().find(|d| d.device_id == device_id) {
            // Update existing device
            device.device_name = device_name;
            device.last_connected = now;
            debug!("Updated existing trusted device: {}", device_id);
        } else {
            // Add new device
            self.devices.push(TrustedDevice {
                device_id: device_id.clone(),
                device_name,
                first_paired: now.clone(),
                last_connected: now,
            });
            info!("Added new trusted device: {}", device_id);
        }

        self.save()
    }

    /// Update the last connected timestamp for a device.
    pub fn update_last_connected(&mut self, device_id: &str) -> Result<()> {
        if let Some(device) = self.devices.iter_mut().find(|d| d.device_id == device_id) {
            device.last_connected = Utc::now().to_rfc3339();
            debug!("Updated last_connected for device: {}", device_id);
            self.save()
        } else {
            anyhow::bail!("Device {} not found in trusted devices", device_id);
        }
    }

    /// Load trusted devices from file.
    fn load(path: &Path) -> Result<Vec<TrustedDevice>> {
        if !path.exists() {
            debug!("Trusted devices file doesn't exist, starting with empty list");
            return Ok(Vec::new());
        }

        let content =
            std::fs::read_to_string(path).with_context(|| format!("Failed to read {:?}", path))?;

        let file: TrustedDevicesFile = serde_json::from_str(&content)
            .with_context(|| "Failed to parse paired_devices.json")?;

        Ok(file.devices)
    }

    /// Save trusted devices to file.
    fn save(&self) -> Result<()> {
        let file = TrustedDevicesFile {
            version: 1,
            devices: self.devices.clone(),
        };

        // Ensure parent directory exists
        if let Some(parent) = self.file_path.parent() {
            std::fs::create_dir_all(parent)?;
        }

        let content = serde_json::to_string_pretty(&file)?;
        std::fs::write(&self.file_path, content)
            .with_context(|| format!("Failed to write {:?}", self.file_path))?;

        debug!("Saved {} trusted device(s)", self.devices.len());
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn test_new_store_empty() -> Result<()> {
        let temp_dir = TempDir::new()?;
        let store = TrustedDeviceStore::new(temp_dir.path())?;
        assert_eq!(store.devices.len(), 0);
        Ok(())
    }

    #[test]
    fn test_add_trusted_device() -> Result<()> {
        let temp_dir = TempDir::new()?;
        let mut store = TrustedDeviceStore::new(temp_dir.path())?;

        store.add_trusted("device-123".to_string(), Some("My Phone".to_string()))?;

        assert!(store.is_trusted("device-123"));
        assert!(!store.is_trusted("device-456"));
        assert_eq!(store.devices.len(), 1);

        Ok(())
    }

    #[test]
    fn test_add_duplicate_device() -> Result<()> {
        let temp_dir = TempDir::new()?;
        let mut store = TrustedDeviceStore::new(temp_dir.path())?;

        store.add_trusted("device-123".to_string(), Some("My Phone".to_string()))?;
        store.add_trusted("device-123".to_string(), Some("My Phone 2".to_string()))?;

        assert_eq!(store.devices.len(), 1);
        assert_eq!(store.devices[0].device_name, Some("My Phone 2".to_string()));

        Ok(())
    }

    #[test]
    fn test_update_last_connected() -> Result<()> {
        let temp_dir = TempDir::new()?;
        let mut store = TrustedDeviceStore::new(temp_dir.path())?;

        store.add_trusted("device-123".to_string(), Some("My Phone".to_string()))?;

        let original_time = store.devices[0].last_connected.clone();

        // Sleep a bit to ensure timestamp changes
        std::thread::sleep(std::time::Duration::from_millis(10));

        store.update_last_connected("device-123")?;

        assert_ne!(store.devices[0].last_connected, original_time);

        Ok(())
    }

    #[test]
    fn test_persistence() -> Result<()> {
        let temp_dir = TempDir::new()?;

        {
            let mut store = TrustedDeviceStore::new(temp_dir.path())?;
            store.add_trusted("device-123".to_string(), Some("My Phone".to_string()))?;
        }

        // Load in new store
        let store = TrustedDeviceStore::new(temp_dir.path())?;
        assert!(store.is_trusted("device-123"));
        assert_eq!(store.devices.len(), 1);

        Ok(())
    }
}
