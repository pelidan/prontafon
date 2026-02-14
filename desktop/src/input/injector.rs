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

//! Text injection abstraction and factory.

use anyhow::Result;
use std::env;
use tracing::{info, warn};

use super::keys::{Key, Modifier};

#[cfg(feature = "x11")]
use super::x11::X11Injector;

use super::wayland::WaylandInjector;

/// Trait for input injection backends.
pub trait InputInjector: Send + Sync {
    /// Get the backend name (e.g., "X11", "Wayland").
    fn backend_name(&self) -> &'static str;

    /// Type text at current cursor position.
    fn type_text(&self, text: &str) -> Result<()>;

    /// Press a single key.
    fn press_key(&self, key: Key) -> Result<()>;

    /// Press a key combination (modifiers + key).
    fn key_combo(&self, modifiers: &[Modifier], key: Key) -> Result<()>;
}

/// Detected display server type.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DisplayServer {
    X11,
    Wayland,
    Unknown,
}

impl DisplayServer {
    /// Detect the current display server.
    pub fn detect() -> Self {
        // Check XDG_SESSION_TYPE first
        if let Ok(session_type) = env::var("XDG_SESSION_TYPE") {
            match session_type.to_lowercase().as_str() {
                "x11" => return DisplayServer::X11,
                "wayland" => return DisplayServer::Wayland,
                _ => {}
            }
        }

        // Check WAYLAND_DISPLAY
        if env::var("WAYLAND_DISPLAY").is_ok() {
            return DisplayServer::Wayland;
        }

        // Check DISPLAY (X11)
        if env::var("DISPLAY").is_ok() {
            return DisplayServer::X11;
        }

        DisplayServer::Unknown
    }
}

/// Create the appropriate input injector for the current display server.
pub fn create_injector() -> Result<Box<dyn InputInjector>> {
    create_injector_with_preference("auto")
}

/// Create input injector with a preference.
///
/// - "auto": Auto-detect display server
/// - "x11": Force X11 backend
/// - "wayland": Force Wayland backend
pub fn create_injector_with_preference(preference: &str) -> Result<Box<dyn InputInjector>> {
    let display_server = match preference.to_lowercase().as_str() {
        "x11" => DisplayServer::X11,
        "wayland" => DisplayServer::Wayland,
        _ => DisplayServer::detect(),
    };

    info!("Display server: {:?}", display_server);

    match display_server {
        DisplayServer::X11 => {
            #[cfg(feature = "x11")]
            {
                info!("Using X11 input injector");
                Ok(Box::new(X11Injector::new()?))
            }
            #[cfg(not(feature = "x11"))]
            {
                Err(anyhow!("X11 support not compiled in"))
            }
        }
        DisplayServer::Wayland => {
            info!("Using Wayland input injector");
            Ok(Box::new(WaylandInjector::new()?))
        }
        DisplayServer::Unknown => {
            warn!("Unknown display server, trying X11 first...");
            #[cfg(feature = "x11")]
            {
                match X11Injector::new() {
                    Ok(injector) => {
                        info!("Using X11 input injector (fallback)");
                        Ok(Box::new(injector))
                    }
                    Err(_) => {
                        info!("Trying Wayland input injector...");
                        Ok(Box::new(WaylandInjector::new()?))
                    }
                }
            }
            #[cfg(not(feature = "x11"))]
            {
                info!("Using Wayland input injector (no X11 support)");
                Ok(Box::new(WaylandInjector::new()?))
            }
        }
    }
}

/// Stub injector for testing without display server.
#[allow(dead_code)]
pub struct StubInjector;

impl InputInjector for StubInjector {
    fn backend_name(&self) -> &'static str {
        "Stub (no-op)"
    }

    fn type_text(&self, text: &str) -> Result<()> {
        info!("[STUB] Would type: {}", text);
        Ok(())
    }

    fn press_key(&self, key: Key) -> Result<()> {
        info!("[STUB] Would press: {:?}", key);
        Ok(())
    }

    fn key_combo(&self, modifiers: &[Modifier], key: Key) -> Result<()> {
        info!("[STUB] Would press: {:?} + {:?}", modifiers, key);
        Ok(())
    }
}
