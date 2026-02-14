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

//! Prontafon Desktop Application

mod bluetooth;
mod commands;
mod config;
mod crypto;
mod events;
mod icons;
mod input;
mod state;
mod storage;
mod ui;

use anyhow::Result;
use gtk4::glib;
use gtk4::prelude::*;
use std::sync::Arc;
use tokio::sync::Mutex;
use tracing::{debug, error, info, warn};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

use bluetooth::GattServer;
use events::EventProcessor;
use state::AppState;
use storage::{TrustedDeviceStore, VoiceCommandStore};

/// Request to show confirmation dialog for pairing.
#[derive(Debug, Clone)]
struct PairingRequest {
    device_id: String,
    device_name: Option<String>,
}

#[tokio::main]
async fn main() -> Result<()> {
    // Initialize logging
    tracing_subscriber::registry()
        .with(tracing_subscriber::fmt::layer())
        .with(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("prontafon_desktop=debug".parse().unwrap()),
        )
        .init();

    info!(
        "Starting Prontafon Desktop v{}...",
        env!("CARGO_PKG_VERSION")
    );

    // Install system tray icons (first run)
    if let Err(e) = icons::install_icons() {
        warn!("Failed to install system icons: {}. Tray icons may not display correctly.", e);
    }

    // Load configuration
    let config = config::Config::load()?;
    info!("Configuration loaded");

    // Initialize trusted device store
    let trusted_store = Arc::new(Mutex::new(TrustedDeviceStore::new(&config.data_dir)?));
    info!("Trusted device store initialized");

    // Initialize voice command store with file watcher
    let voice_command_store = match VoiceCommandStore::new_with_watcher(&config.data_dir) {
        Ok(store) => {
            let store = Arc::new(store);
            info!("Voice command store initialized at {:?}", store.config_path());
            Some(store)
        }
        Err(e) => {
            warn!("Failed to initialize voice command store: {}. Voice commands will use defaults only.", e);
            None
        }
    };

    // Initialize GTK (required for PIN dialog)
    gtk4::init().expect("Failed to initialize GTK");
    let gtk_app = gtk4::Application::builder()
        .application_id("com.prontafon.desktop")
        .build();
    // Register the application so windows can be created
    gtk_app.register(None::<&gtk4::gio::Cancellable>)?;
    info!("GTK initialized");

    // Initialize input injector
    let injector = input::create_injector()?;
    info!("Input injector: {}", injector.backend_name());

    // Create application state
    let state = AppState::new();

    // Initialize BLE GATT server
    info!("Initializing BLE GATT server...");
    let (gatt_event_tx, gatt_event_rx) = tokio::sync::mpsc::channel::<bluetooth::ConnectionEvent>(32);
    let gatt_server = Arc::new(Mutex::new(GattServer::new(gatt_event_tx).await?));
    {
        let mut server = gatt_server.lock().await;
        server.set_name(&config.bluetooth.device_name).await?;
        server.start().await?;
    }
    info!(
        "BLE GATT server started and advertising as '{}'",
        config.bluetooth.device_name
    );

    // Create channel for pairing requests
    let (pairing_tx, mut pairing_rx) = tokio::sync::mpsc::channel::<PairingRequest>(8);

    // Create event processor
    let processor = if let Some(store) = voice_command_store.clone() {
        EventProcessor::with_voice_commands(injector, store, state.clone())
    } else {
        EventProcessor::new(injector)
    };

    // Start system tray
    let (mut action_rx, tray_handle) = ui::run_tray(state.clone())?;
    
    info!("Ready. System tray active.");

    // Handle BLE GATT events
    let state_gatt = state.clone();
    let mut gatt_event_rx_state = gatt_event_rx;
    let tray_handle_gatt = tray_handle.clone();
    
    tokio::spawn(async move {
        let mut processor_gatt = processor;
        
        // Periodic flush interval for look-ahead and stale word handling
        let mut flush_interval = tokio::time::interval(tokio::time::Duration::from_millis(50));
        
        loop {
            tokio::select! {
                Some(event) = gatt_event_rx_state.recv() => {
                    // Update state
                    match &event {
                        bluetooth::ConnectionEvent::Connected { device_name } => {
                            info!("BLE device connected: {}", device_name);
                            state_gatt.set_connected(device_name.clone());
                            tray_handle_gatt.update(|_| {});
                        }
                        bluetooth::ConnectionEvent::Disconnected => {
                            info!("BLE device disconnected");
                            state_gatt.set_disconnected();
                            tray_handle_gatt.update(|_| {});
                        }
                        bluetooth::ConnectionEvent::Error(e) => {
                            error!("BLE error: {}", e);
                            state_gatt.set_error();
                            tray_handle_gatt.update(|_| {});
                        }
                        bluetooth::ConnectionEvent::TextReceived(text) => {
                            debug!("BLE text received: {}", text);
                        }
                        bluetooth::ConnectionEvent::WordReceived { word, seq, session } => {
                            debug!("BLE word received: '{}' seq={:?} session={}", word, seq, session);
                            // Word processing is handled by event processor
                        }
                        bluetooth::ConnectionEvent::PairRequested { device_id, device_name } => {
                            info!("📱 BLE pairing requested by: {}", device_id);
                            info!("📤 Forwarding to main loop for confirmation dialog...");
                            // Send to main loop for confirmation dialog handling
                            let _ = pairing_tx.send(PairingRequest { 
                                device_id: device_id.clone(),
                                device_name: device_name.clone(),
                            }).await;
                            info!("✅ Pairing request forwarded to main loop");
                        }
                        bluetooth::ConnectionEvent::CommandReceived(_) => {
                            // Will be processed below
                        }
                    }
                    
                    // Process event
                    if let Err(e) = processor_gatt.process_event(event).await {
                        error!("Error processing BLE event: {}", e);
                    }
                }
                _ = flush_interval.tick() => {
                    // Periodic flush of pending/stale words
                    if let Err(e) = processor_gatt.process_periodic_flush().await {
                        error!("Error during periodic flush: {}", e);
                    }
                }
                else => {
                    // Channel closed, exit the loop
                    break;
                }
            }
        }
    });

    // Handle tray actions and pairing requests
    // Use a short timeout to ensure GTK events are processed regularly
    let gtk_poll_interval = tokio::time::Duration::from_millis(10);
    
    loop {
        // Process any pending GTK events (non-blocking)
        while gtk4::glib::MainContext::default().pending() {
            gtk4::glib::MainContext::default().iteration(false);
        }
        
        tokio::select! {
            biased;  // Check channels first, then fall through to timeout
            
            Some(action) = action_rx.recv() => {
                match action {
                    ui::TrayAction::ManageCommands => {
                        info!("Manage Commands window requested");
                        // Window will be opened and events handled in the GTK main context
                        // The manage_commands window has its own event handling via periodic refresh
                        if let Some(store) = voice_command_store.clone() {
                            let mut event_rx = ui::show_manage_commands_window(&gtk_app, store.clone(), state.clone());
                            
                            // Handle events from the manage commands window using GTK's event loop
                            let state_cmds = state.clone();
                            let store_cmds = store.clone();
                            let gtk_app_cmds = gtk_app.clone();
                            
                            // Use glib::timeout to poll the channel from the GTK thread
                            glib::timeout_add_local(std::time::Duration::from_millis(50), move || {
                                match event_rx.try_recv() {
                                    Ok(event) => {
                                        match event {
                                            ui::ManageCommandsEvent::StartRecording(command) => {
                                                info!("Starting recording for command: {}", command);
                                                state_cmds.start_recording(command.clone());
                                                // Show recording dialog immediately (we're on GTK thread)
                                                ui::show_recording_dialog(&gtk_app_cmds, &command, state_cmds.clone());
                                            }
                                            ui::ManageCommandsEvent::CancelRecording => {
                                                info!("Recording cancelled");
                                                state_cmds.stop_recording();
                                            }
                                            ui::ManageCommandsEvent::RevertToDefault(command) => {
                                                info!("Reverting command '{}' to default", command);
                                                if let Err(e) = store_cmds.revert_to_default(&command) {
                                                    error!("Failed to revert command: {}", e);
                                                }
                                            }
                                        }
                                        glib::ControlFlow::Continue
                                    }
                                    Err(tokio::sync::mpsc::error::TryRecvError::Empty) => {
                                        // No event, keep polling
                                        glib::ControlFlow::Continue
                                    }
                                    Err(tokio::sync::mpsc::error::TryRecvError::Disconnected) => {
                                        // Channel closed (window closed)
                                        info!("Manage Commands window closed");
                                        glib::ControlFlow::Break
                                    }
                                }
                            });
                        } else {
                            warn!("Voice command store not available");
                        }
                    }
                    ui::TrayAction::Quit => {
                        info!("Quit requested");
                        break;
                    }
                }
            }
            Some(request) = pairing_rx.recv() => {
                let display_name = request.device_name.clone().unwrap_or_else(|| request.device_id.clone());
                info!("🔔 Received pairing request in main loop for: {}", display_name);
                
                // Check if auto-accept is enabled and device is trusted
                let should_auto_accept = {
                    let store = trusted_store.lock().await;
                    config.bluetooth.auto_accept && store.is_trusted(&request.device_id)
                };
                
                if should_auto_accept {
                    info!("🤖 Auto-accepting trusted device: {}", request.device_id);
                    
                    // Complete pairing automatically (release gatt_server before acquiring trusted_store)
                    let pairing_result = {
                        let server = gatt_server.lock().await;
                        server.complete_pairing().await
                    };
                    match pairing_result {
                        Ok(_) => {
                            info!("🎉 Auto-pairing completed successfully!");
                            // Update last connected timestamp
                            let mut store = trusted_store.lock().await;
                            if let Err(e) = store.update_last_connected(&request.device_id) {
                                error!("Failed to update last_connected: {}", e);
                            }
                            tray_handle.update(|_| {});
                        }
                        Err(e) => {
                            error!("❌ Auto-pairing failed: {}", e);
                        }
                    }
                    continue;
                }
                
                info!("🪟 Showing confirmation dialog...");
                
                // Show confirmation dialog
                let mut confirm_rx = ui::show_confirmation_dialog(&gtk_app, &display_name);
                info!("✅ Confirmation dialog shown, waiting for user response...");
                
                // Process GTK events until dialog closes
                let result = loop {
                    while gtk4::glib::MainContext::default().pending() {
                        gtk4::glib::MainContext::default().iteration(false);
                    }
                    
                    match confirm_rx.try_recv() {
                        Ok(result) => break result,
                        Err(tokio::sync::oneshot::error::TryRecvError::Empty) => {
                            tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
                        }
                        Err(tokio::sync::oneshot::error::TryRecvError::Closed) => {
                            break ui::ConfirmationResult::Rejected;
                        }
                    }
                };
                
                // Handle result
                info!("👤 User response received: {:?}", if matches!(result, ui::ConfirmationResult::Approved) { "APPROVED" } else { "REJECTED" });
                match result {
                    ui::ConfirmationResult::Approved => {
                        info!("✅ User approved pairing, completing ECDH exchange...");
                        let pairing_result = {
                            let server = gatt_server.lock().await;
                            server.complete_pairing().await
                        };
                        match pairing_result {
                            Ok(_) => {
                                info!("🎉 Pairing completed successfully!");
                                let mut store = trusted_store.lock().await;
                                if let Err(e) = store.add_trusted(request.device_id.clone(), request.device_name.clone()) {
                                    error!("Failed to add device to trusted store: {}", e);
                                } else {
                                    info!("Device added to trusted store for auto-accept");
                                }
                                tray_handle.update(|_| {});
                            }
                            Err(e) => {
                                error!("❌ Pairing failed: {}", e);
                            }
                        }
                    }
                    ui::ConfirmationResult::Rejected => {
                        info!("❌ User rejected pairing, sending rejection...");
                        let server = gatt_server.lock().await;
                        if let Err(e) = server.reject_pairing("User rejected").await {
                            error!("❌ Failed to send rejection: {}", e);
                        } else {
                            info!("✅ Rejection sent to Android");
                        }
                    }
                }
            }
            _ = tokio::signal::ctrl_c() => {
                info!("Shutdown signal received");
                break;
            }
            // Timeout to ensure GTK events are processed regularly even when no other events arrive
            _ = tokio::time::sleep(gtk_poll_interval) => {
                // Just wake up to process GTK events at the top of the loop
            }
        }
    }

    info!("Prontafon Desktop stopped");
    Ok(())
}
