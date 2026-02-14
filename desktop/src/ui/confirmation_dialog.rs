// Copyright 2026 Daniel Pelikan
// SPDX-License-Identifier: Apache-2.0

//! Confirmation dialog for pairing approval.

use gtk4::prelude::*;
use gtk4::{Application, ApplicationWindow, Box as GtkBox, Button, Label, Orientation};
use std::sync::Arc;
use tokio::sync::oneshot;
use tracing::info;

/// Result of confirmation dialog.
pub enum ConfirmationResult {
    /// User approved the connection.
    Approved,
    /// User rejected the connection.
    Rejected,
}

/// Show connection confirmation dialog.
///
/// Returns Approved if user clicks Yes, Rejected if user clicks No or closes the dialog.
pub fn show_confirmation_dialog(
    app: &Application,
    device_name: &str,
) -> oneshot::Receiver<ConfirmationResult> {
    info!(
        "🪟 Creating confirmation dialog for device: {}",
        device_name
    );
    let (tx, rx) = oneshot::channel();
    let tx = Arc::new(std::sync::Mutex::new(Some(tx)));

    let window = ApplicationWindow::builder()
        .application(app)
        .title("Prontafon - Pairing Request")
        .default_width(350)
        .default_height(180)
        .modal(true)
        .resizable(false)
        .build();

    info!("✅ Dialog window created");

    let main_box = GtkBox::new(Orientation::Vertical, 16);
    main_box.set_margin_top(24);
    main_box.set_margin_bottom(24);
    main_box.set_margin_start(24);
    main_box.set_margin_end(24);

    // Title
    let title = Label::new(Some("Connection Request"));
    title.add_css_class("title-2");
    main_box.append(&title);

    // Device info - make device name prominent
    let device_label = Label::new(Some(&format!("\"{}\"", device_name)));
    device_label.add_css_class("title-3");
    main_box.append(&device_label);

    // Question
    let question = Label::new(Some("wants to connect to this computer."));
    main_box.append(&question);

    // Security note
    let note = Label::new(Some("Only approve if you initiated this connection."));
    note.add_css_class("dim-label");
    note.set_wrap(true);
    main_box.append(&note);

    // Buttons - Yes/No with proper emphasis
    let button_box = GtkBox::new(Orientation::Horizontal, 12);
    button_box.set_halign(gtk4::Align::Center);
    button_box.set_margin_top(8);

    let reject_button = Button::with_label("No");
    reject_button.set_width_request(80);

    let approve_button = Button::with_label("Yes");
    approve_button.add_css_class("suggested-action");
    approve_button.set_width_request(80);

    button_box.append(&reject_button);
    button_box.append(&approve_button);
    main_box.append(&button_box);

    window.set_child(Some(&main_box));

    // Reject handler
    let window_reject = window.clone();
    let tx_reject = tx.clone();
    reject_button.connect_clicked(move |_| {
        info!("❌ User clicked 'No' button");
        if let Some(tx) = tx_reject.lock().unwrap().take() {
            let _ = tx.send(ConfirmationResult::Rejected);
        }
        window_reject.close();
    });

    // Approve handler
    let window_approve = window.clone();
    let tx_approve = tx.clone();
    approve_button.connect_clicked(move |_| {
        info!("✅ User clicked 'Yes' button - approving pairing");
        if let Some(tx) = tx_approve.lock().unwrap().take() {
            let _ = tx.send(ConfirmationResult::Approved);
        }
        window_approve.close();
    });

    // Window close handler (X button = reject)
    let tx_close = tx.clone();
    window.connect_close_request(move |_| {
        info!("❌ User closed dialog window");
        if let Some(tx) = tx_close.lock().unwrap().take() {
            let _ = tx.send(ConfirmationResult::Rejected);
        }
        glib::Propagation::Proceed
    });

    // Auto-close after 60 seconds (reject)
    // Only log timeout if we're actually timing out (tx not already taken)
    let window_timeout = window.clone();
    let tx_timeout = tx.clone();
    glib::timeout_add_seconds_local_once(60, move || {
        // Only log and act if the dialog wasn't already handled
        if let Some(tx) = tx_timeout.lock().unwrap().take() {
            info!("⏱️  Dialog timeout (60s) - auto-rejecting");
            let _ = tx.send(ConfirmationResult::Rejected);
            window_timeout.close();
        }
        // If tx was already taken, the dialog was handled - do nothing
    });

    info!("📺 Presenting dialog window to user...");
    window.present();
    approve_button.grab_focus(); // Focus on Yes button for quick approval
    info!("✅ Dialog is now visible and awaiting user input");

    rx
}
