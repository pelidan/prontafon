// Copyright 2024 Prontafon Contributors
// SPDX-License-Identifier: Apache-2.0

//! Manage Commands window for recording and configuring voice command phrases.

use gtk4::prelude::*;
use gtk4::{
    Application, ApplicationWindow, Box as GtkBox, Button, Label, ListBox, ListBoxRow, Orientation,
    ScrolledWindow, SelectionMode,
};
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::mpsc;
use tracing::{error, info};

use crate::state::AppState;
use crate::storage::{CommandInfo, VoiceCommandStore};

/// Events from the manage commands window.
#[derive(Debug, Clone)]
pub enum ManageCommandsEvent {
    /// Start recording for a command.
    StartRecording(String),
    /// Cancel recording.
    CancelRecording,
    /// Revert command to default phrase.
    RevertToDefault(String),
}

/// Create and show the Manage Commands window (non-modal).
pub fn show_manage_commands_window(
    app: &Application,
    store: Arc<VoiceCommandStore>,
    state: Arc<AppState>,
) -> mpsc::UnboundedReceiver<ManageCommandsEvent> {
    let (event_tx, event_rx) = mpsc::unbounded_channel();

    let window = ApplicationWindow::builder()
        .application(app)
        .title("Prontafon - Manage Voice Commands")
        .default_width(550)
        .default_height(400)
        .modal(false) // Non-modal as requested
        .build();

    let main_box = GtkBox::new(Orientation::Vertical, 12);
    main_box.set_margin_top(16);
    main_box.set_margin_bottom(16);
    main_box.set_margin_start(16);
    main_box.set_margin_end(16);

    // Header
    let header = Label::new(Some("Voice Command Mappings"));
    header.add_css_class("title-2");
    main_box.append(&header);

    let subtitle = Label::new(Some(
        "Click \"Record\" to set a custom phrase for a command.",
    ));
    subtitle.add_css_class("dim-label");
    main_box.append(&subtitle);

    // Command list in scrolled window
    let scrolled = ScrolledWindow::builder()
        .hexpand(true)
        .vexpand(true)
        .build();

    let list_box = ListBox::new();
    list_box.set_selection_mode(SelectionMode::None);
    list_box.add_css_class("boxed-list");
    scrolled.set_child(Some(&list_box));
    main_box.append(&scrolled);

    // Populate command list
    populate_command_list(&list_box, &store, &state, &event_tx);

    // Legend and close button
    let footer_box = GtkBox::new(Orientation::Horizontal, 8);

    let legend = Label::new(Some("✱ = custom phrase"));
    legend.add_css_class("dim-label");
    legend.set_hexpand(true);
    legend.set_halign(gtk4::Align::Start);
    footer_box.append(&legend);

    let close_button = Button::with_label("Close");
    close_button.set_width_request(100);
    footer_box.append(&close_button);
    main_box.append(&footer_box);

    window.set_child(Some(&main_box));

    // Close handler
    let window_close = window.clone();
    close_button.connect_clicked(move |_| {
        window_close.close();
    });

    // Refresh list periodically to show recording state changes
    let list_box_ref = list_box.clone();
    let store_ref = store.clone();
    let state_ref = state.clone();
    let event_tx_ref = event_tx.clone();

    glib::timeout_add_local(Duration::from_millis(500), move || {
        if list_box_ref.parent().is_none() {
            // Window closed
            return glib::ControlFlow::Break;
        }
        populate_command_list(&list_box_ref, &store_ref, &state_ref, &event_tx_ref);
        glib::ControlFlow::Continue
    });

    window.present();
    info!("Manage Commands window opened");

    event_rx
}

/// Populate the command list.
fn populate_command_list(
    list_box: &ListBox,
    store: &VoiceCommandStore,
    state: &AppState,
    event_tx: &mpsc::UnboundedSender<ManageCommandsEvent>,
) {
    // Remove existing rows
    while let Some(child) = list_box.first_child() {
        list_box.remove(&child);
    }

    let commands = store.get_all_commands();
    let recording_command = state.get_recording_command();

    for cmd_info in commands {
        let row = create_command_row(&cmd_info, &recording_command, event_tx);
        list_box.append(&row);
    }
}

/// Create a row for a command.
fn create_command_row(
    cmd_info: &CommandInfo,
    recording_command: &Option<String>,
    event_tx: &mpsc::UnboundedSender<ManageCommandsEvent>,
) -> ListBoxRow {
    let row = ListBoxRow::new();
    row.set_activatable(false);
    row.set_selectable(false);

    let hbox = GtkBox::new(Orientation::Horizontal, 12);
    hbox.set_margin_top(8);
    hbox.set_margin_bottom(8);
    hbox.set_margin_start(12);
    hbox.set_margin_end(12);

    // Command name
    let cmd_label = Label::new(Some(&cmd_info.command));
    cmd_label.add_css_class("heading");
    cmd_label.set_width_chars(12);
    cmd_label.set_xalign(0.0);
    hbox.append(&cmd_label);

    // Current phrase
    let phrase_text = if cmd_info.is_custom {
        format!("\"{}\" ✱", cmd_info.phrase)
    } else {
        format!("\"{}\" (default)", cmd_info.phrase)
    };
    let phrase_label = Label::new(Some(&phrase_text));
    phrase_label.set_hexpand(true);
    phrase_label.set_xalign(0.0);
    if cmd_info.is_custom {
        phrase_label.add_css_class("accent");
    } else {
        phrase_label.add_css_class("dim-label");
    }
    hbox.append(&phrase_label);

    // Check if this command is being recorded
    let is_recording_this = recording_command.as_ref() == Some(&cmd_info.command);
    let is_recording_other = recording_command.is_some() && !is_recording_this;

    // Record/Cancel button
    let record_button = if is_recording_this {
        let btn = Button::with_label("Cancel");
        btn.add_css_class("destructive-action");
        btn
    } else {
        let btn = Button::with_label("Record");
        if is_recording_other {
            btn.set_sensitive(false);
        }
        btn
    };
    record_button.set_width_request(80);

    let command_record = cmd_info.command.clone();
    let tx_record = event_tx.clone();
    let is_recording = is_recording_this;
    record_button.connect_clicked(move |_| {
        if is_recording {
            info!("Cancel button clicked for recording");
            if let Err(e) = tx_record.send(ManageCommandsEvent::CancelRecording) {
                error!("Failed to send CancelRecording event: {}", e);
            }
        } else {
            info!("Record button clicked for command: {}", command_record);
            if let Err(e) =
                tx_record.send(ManageCommandsEvent::StartRecording(command_record.clone()))
            {
                error!("Failed to send StartRecording event: {}", e);
            }
        }
    });
    hbox.append(&record_button);

    // Revert button
    let revert_button = Button::with_label("Revert");
    revert_button.set_width_request(80);
    revert_button.set_sensitive(cmd_info.is_custom && !is_recording_this);

    let command_revert = cmd_info.command.clone();
    let tx_revert = event_tx.clone();
    revert_button.connect_clicked(move |_| {
        let _ = tx_revert.send(ManageCommandsEvent::RevertToDefault(command_revert.clone()));
    });
    hbox.append(&revert_button);

    row.set_child(Some(&hbox));
    row
}

/// Recording timeout duration in seconds.
const RECORDING_TIMEOUT_SECS: u64 = 30;

/// Show the recording dialog with 30-second timeout.
pub fn show_recording_dialog(app: &Application, command: &str, state: Arc<AppState>) {
    let dialog = ApplicationWindow::builder()
        .application(app)
        .title("Recording...")
        .default_width(350)
        .default_height(200)
        .modal(true)
        .resizable(false)
        .build();

    let main_box = GtkBox::new(Orientation::Vertical, 16);
    main_box.set_margin_top(24);
    main_box.set_margin_bottom(24);
    main_box.set_margin_start(24);
    main_box.set_margin_end(24);

    // Title
    let title = Label::new(Some(&format!("Recording phrase for {}", command)));
    title.add_css_class("title-3");
    main_box.append(&title);

    // Instructions
    let instructions = Label::new(Some(
        "Speak the phrase you want to use for this command using your Android device.",
    ));
    instructions.set_wrap(true);
    instructions.set_max_width_chars(40);
    main_box.append(&instructions);

    // Countdown timer label
    let countdown_label = Label::new(Some(&format!(
        "🎤 Listening... ({}s remaining)",
        RECORDING_TIMEOUT_SECS
    )));
    countdown_label.add_css_class("title-4");
    main_box.append(&countdown_label);

    // Cancel button
    let button_box = GtkBox::new(Orientation::Horizontal, 0);
    button_box.set_halign(gtk4::Align::Center);
    let cancel_button = Button::with_label("Cancel");
    cancel_button.set_width_request(100);
    button_box.append(&cancel_button);
    main_box.append(&button_box);

    dialog.set_child(Some(&main_box));

    // Track start time for countdown
    let start_time = Rc::new(RefCell::new(Instant::now()));

    // Cancel handler
    let dialog_cancel = dialog.clone();
    let state_cancel = state.clone();
    cancel_button.connect_clicked(move |_| {
        state_cancel.stop_recording();
        dialog_cancel.close();
    });

    // Close on window X
    let state_close = state.clone();
    dialog.connect_close_request(move |_| {
        state_close.stop_recording();
        glib::Propagation::Proceed
    });

    // Update countdown and auto-close on timeout or when recording stops
    let dialog_check = dialog.clone();
    let state_check = state.clone();
    let countdown_label_ref = countdown_label.clone();
    let start_time_ref = start_time.clone();

    glib::timeout_add_local(Duration::from_millis(100), move || {
        // Check if recording stopped (phrase captured or cancelled)
        if !state_check.is_recording() {
            dialog_check.close();
            return glib::ControlFlow::Break;
        }

        // Check timeout
        let elapsed = start_time_ref.borrow().elapsed().as_secs();
        let remaining = RECORDING_TIMEOUT_SECS.saturating_sub(elapsed);

        if remaining == 0 {
            // Timeout reached
            info!("Recording timeout reached");
            state_check.stop_recording();
            dialog_check.close();
            return glib::ControlFlow::Break;
        }

        // Update countdown label
        countdown_label_ref.set_text(&format!("🎤 Listening... ({}s remaining)", remaining));

        glib::ControlFlow::Continue
    });

    dialog.present();
    info!("Recording dialog opened for command: {}", command);
}
