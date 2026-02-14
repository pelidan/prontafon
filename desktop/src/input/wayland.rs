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

//! Wayland text injection using ydotool.

use anyhow::{anyhow, Result};
use std::process::Command;
use tracing::{debug, warn};

use super::keys::{Key, Modifier};
use super::InputInjector;

/// Wayland input injector using ydotool.
pub struct WaylandInjector {
    /// Whether ydotool daemon is available.
    ydotool_available: bool,
}

impl WaylandInjector {
    /// Create a new Wayland injector.
    pub fn new() -> Result<Self> {
        // Check if ydotool is available
        let ydotool_available = Command::new("which")
            .arg("ydotool")
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false);

        if !ydotool_available {
            warn!("ydotool not found in PATH");
        }

        // Check if ydotoold is running
        let daemon_running = Command::new("pgrep")
            .arg("ydotoold")
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false);

        if !daemon_running {
            warn!("ydotoold daemon not running. Start with: sudo systemctl start ydotool");
        }

        Ok(Self { ydotool_available })
    }

    /// Run ydotool command.
    fn run_ydotool(&self, args: &[&str]) -> Result<()> {
        if !self.ydotool_available {
            return Err(anyhow!("ydotool not available"));
        }

        debug!("Running: ydotool {:?}", args);

        let output = Command::new("ydotool")
            .args(args)
            .output()
            .map_err(|e| anyhow!("Failed to run ydotool: {}", e))?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(anyhow!("ydotool failed: {}", stderr));
        }

        Ok(())
    }
}

impl InputInjector for WaylandInjector {
    fn backend_name(&self) -> &'static str {
        "Wayland (ydotool)"
    }

    fn type_text(&self, text: &str) -> Result<()> {
        debug!("Typing text: {} chars", text.len());

        // ydotool type command
        self.run_ydotool(&["type", "--clearmodifiers", text])
    }

    fn press_key(&self, key: Key) -> Result<()> {
        debug!("Pressing key: {:?}", key);

        let key_name = key.to_ydotool();
        self.run_ydotool(&["key", key_name])
    }

    fn key_combo(&self, modifiers: &[Modifier], key: Key) -> Result<()> {
        debug!("Key combo: {:?} + {:?}", modifiers, key);

        // Build key combo string: "CTRL+A"
        let mut combo = String::new();
        for modifier in modifiers {
            combo.push_str(modifier.to_ydotool());
            combo.push('+');
        }
        combo.push_str(key.to_ydotool());

        self.run_ydotool(&["key", &combo])
    }
}
