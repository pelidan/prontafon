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

//! System icon installation and management.

use anyhow::{Context, Result};
use std::fs;
use std::path::PathBuf;
use tracing::{info, warn};

/// Icon files embedded in the binary.
const ICON_CONNECTED_SYMBOLIC: &str =
    include_str!("../resources/icons/prontafon-connected-symbolic.svg");
const ICON_DISCONNECTED_SYMBOLIC: &str =
    include_str!("../resources/icons/prontafon-disconnected-symbolic.svg");
const ICON_CONNECTED: &str = include_str!("../resources/icons/prontafon-connected.svg");
const ICON_DISCONNECTED: &str = include_str!("../resources/icons/prontafon-disconnected.svg");

/// Icon installation paths.
struct IconPaths {
    symbolic_dir: PathBuf,
    scalable_dir: PathBuf,
}

impl IconPaths {
    /// Get the icon installation directories.
    fn new() -> Result<Self> {
        let data_dir = dirs::data_local_dir().context("Failed to get local data directory")?;

        let icon_base = data_dir.join("icons").join("hicolor");

        Ok(Self {
            symbolic_dir: icon_base.join("symbolic").join("apps"),
            scalable_dir: icon_base.join("scalable").join("apps"),
        })
    }
}

/// Check if icons are already installed.
fn are_icons_installed(paths: &IconPaths) -> bool {
    let required_files = [
        paths.symbolic_dir.join("prontafon-connected-symbolic.svg"),
        paths
            .symbolic_dir
            .join("prontafon-disconnected-symbolic.svg"),
        paths.scalable_dir.join("prontafon-connected.svg"),
        paths.scalable_dir.join("prontafon-disconnected.svg"),
    ];

    required_files.iter().all(|p| p.exists())
}

/// Install icon file to the specified directory.
fn install_icon(dir: &PathBuf, filename: &str, content: &str) -> Result<()> {
    fs::create_dir_all(dir)
        .with_context(|| format!("Failed to create directory: {}", dir.display()))?;

    let path = dir.join(filename);
    fs::write(&path, content)
        .with_context(|| format!("Failed to write icon file: {}", path.display()))?;

    info!("Installed icon: {}", path.display());
    Ok(())
}

/// Update icon cache using gtk-update-icon-cache if available.
fn update_icon_cache() {
    let data_dir = match dirs::data_local_dir() {
        Some(dir) => dir,
        None => {
            warn!("Could not determine data directory for icon cache update");
            return;
        }
    };

    let icon_dir = data_dir.join("icons").join("hicolor");

    // Try to run gtk-update-icon-cache
    match std::process::Command::new("gtk-update-icon-cache")
        .arg("-f")
        .arg("-t")
        .arg(&icon_dir)
        .output()
    {
        Ok(output) => {
            if output.status.success() {
                info!("Updated icon cache successfully");
            } else {
                warn!(
                    "gtk-update-icon-cache failed: {}",
                    String::from_utf8_lossy(&output.stderr)
                );
            }
        }
        Err(e) => {
            warn!(
                "Could not run gtk-update-icon-cache: {}. Icons may not appear immediately.",
                e
            );
        }
    }
}

/// Install Prontafon system tray icons to the user's icon directory.
///
/// This installs both symbolic (for theme-aware recoloring) and regular variants
/// to ~/.local/share/icons/hicolor/.
pub fn install_icons() -> Result<()> {
    let paths = IconPaths::new()?;

    // Check if already installed
    if are_icons_installed(&paths) {
        info!("Icons already installed, skipping installation");
        return Ok(());
    }

    info!("Installing Prontafon system tray icons...");

    // Install symbolic icons (for theme-aware recoloring)
    install_icon(
        &paths.symbolic_dir,
        "prontafon-connected-symbolic.svg",
        ICON_CONNECTED_SYMBOLIC,
    )?;

    install_icon(
        &paths.symbolic_dir,
        "prontafon-disconnected-symbolic.svg",
        ICON_DISCONNECTED_SYMBOLIC,
    )?;

    // Install regular icons (fallback)
    install_icon(
        &paths.scalable_dir,
        "prontafon-connected.svg",
        ICON_CONNECTED,
    )?;

    install_icon(
        &paths.scalable_dir,
        "prontafon-disconnected.svg",
        ICON_DISCONNECTED,
    )?;

    // Update icon cache
    update_icon_cache();

    info!("Icon installation complete");
    Ok(())
}
