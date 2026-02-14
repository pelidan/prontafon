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

//! Event processing and message dispatch.

use anyhow::Result;
use std::sync::Arc;
use tracing::{debug, error, info, warn};

use crate::bluetooth::ConnectionEvent;
use crate::commands::{
    CombinedMatcher, MatchResult, ProcessedItem, TextSegment, VoiceCommand, WordBuffer,
};
use crate::input::InputInjector;
use crate::state::AppState;
use crate::storage::VoiceCommandStore;

/// Process events from Bluetooth connections.
pub struct EventProcessor {
    injector: Box<dyn InputInjector>,
    voice_command_store: Option<Arc<VoiceCommandStore>>,
    state: Option<Arc<AppState>>,
    matcher: Option<CombinedMatcher>,
    word_buffer: WordBuffer,
}

impl EventProcessor {
    /// Create a new event processor.
    pub fn new(injector: Box<dyn InputInjector>) -> Self {
        Self {
            injector,
            voice_command_store: None,
            state: None,
            matcher: None,
            word_buffer: WordBuffer::new(),
        }
    }

    /// Create a new event processor with voice command support.
    pub fn with_voice_commands(
        injector: Box<dyn InputInjector>,
        voice_command_store: Arc<VoiceCommandStore>,
        state: Arc<AppState>,
    ) -> Self {
        let matcher = CombinedMatcher::new(voice_command_store.clone());
        Self {
            injector,
            voice_command_store: Some(voice_command_store),
            state: Some(state),
            matcher: Some(matcher),
            word_buffer: WordBuffer::new(),
        }
    }

    /// Process a single event.
    pub async fn process_event(&mut self, event: ConnectionEvent) -> Result<()> {
        match event {
            ConnectionEvent::TextReceived(text) => {
                self.handle_text(&text).await?;
            }
            ConnectionEvent::WordReceived { word, seq, session } => {
                self.handle_word(&word, seq, &session).await?;
            }
            ConnectionEvent::CommandReceived(cmd) => {
                self.handle_command(&cmd).await?;
            }
            ConnectionEvent::Connected { device_name } => {
                info!("Device connected: {}", device_name);
                // Reset word buffer state for the new connection to prevent
                // stale session/sequence state from blocking words
                self.word_buffer.reset();
                info!("Word buffer reset for new connection");
            }
            ConnectionEvent::Disconnected => {
                info!("Device disconnected");
            }
            ConnectionEvent::PairRequested {
                device_id,
                device_name,
            } => {
                info!(
                    "Pairing requested by: {} ({})",
                    device_name.as_deref().unwrap_or("Unknown"),
                    device_id
                );
                // Handled by main event loop
            }
        }
        Ok(())
    }

    /// Handle received text.
    async fn handle_text(&mut self, text: &str) -> Result<()> {
        debug!("Processing text: {} chars", text.len());

        // Check if we're in recording mode
        if let (Some(state), Some(store)) = (&self.state, &self.voice_command_store) {
            if let Some(command) = state.get_recording_command() {
                // We're recording - save this text as the phrase for the command
                info!("Recording phrase '{}' for command '{}'", text, command);
                if let Err(e) = store.set_phrase(&command, text) {
                    error!("Failed to save phrase: {}", e);
                } else {
                    info!(
                        "Successfully saved phrase '{}' for command '{}'",
                        text, command
                    );
                }
                // Stop recording mode
                state.stop_recording();
                return Ok(());
            }
        }

        // Check if this text matches a voice command phrase (with context support)
        if let Some(matcher) = &self.matcher {
            match matcher.match_with_context(text) {
                MatchResult::ExactCommand(voice_cmd) => {
                    // Entire text is a command
                    info!("Text '{}' matched voice command: {:?}", text, voice_cmd);
                    if let Err(e) = crate::commands::execute(&voice_cmd, self.injector.as_ref()) {
                        error!("Failed to execute voice command: {}", e);
                    }
                    return Ok(());
                }
                MatchResult::MidTextCommand(segments) => {
                    // Command found within text - process segments in order
                    debug!(
                        "Found command within text, processing {} segments",
                        segments.len()
                    );
                    for segment in segments {
                        match segment {
                            TextSegment::Text(text_part) => {
                                debug!("Typing text segment: {} chars", text_part.len());
                                if let Err(e) = self.injector.type_text(&text_part) {
                                    error!("Failed to inject text segment: {}", e);
                                }
                            }
                            TextSegment::Command(cmd) => {
                                debug!("Executing command segment: {:?}", cmd);
                                if let Err(e) =
                                    crate::commands::execute(&cmd, self.injector.as_ref())
                                {
                                    error!("Failed to execute command segment: {}", e);
                                }
                            }
                        }
                    }
                    debug!("Mid-text command processing complete");
                    return Ok(());
                }
                MatchResult::NoMatch => {
                    // Fall through to regular text injection
                }
            }
        }

        // Inject text (no command match)
        debug!("Injecting text into active window: {} chars", text.len());
        if let Err(e) = self.injector.type_text(text) {
            error!("Failed to inject text: {}", e);
        } else {
            debug!("Text injection successful");
        }

        Ok(())
    }

    /// Handle received command.
    async fn handle_command(&mut self, cmd: &str) -> Result<()> {
        debug!("Processing command: {}", cmd);

        // Parse and execute command
        if let Some(voice_cmd) = VoiceCommand::parse(cmd) {
            if let Err(e) = crate::commands::execute(&voice_cmd, self.injector.as_ref()) {
                error!("Failed to execute command: {}", e);
            }
        } else {
            warn!("Unknown command: {}", cmd);
        }

        Ok(())
    }

    /// Handle received word (from word-by-word streaming).
    async fn handle_word(&mut self, word: &str, seq: Option<u64>, session: &str) -> Result<()> {
        debug!(
            "Processing word: '{}' seq={:?} session={}",
            word, seq, session
        );

        // Create closures for the matcher functions
        let matcher = self.matcher.as_ref();

        let single_word_matcher =
            |w: &str| -> Option<String> { matcher.and_then(|m| m.match_single_word(w)) };

        let two_word_matcher = |w1: &str, w2: &str| -> Option<String> {
            matcher.and_then(|m| m.match_two_words(w1, w2))
        };

        let could_start = |w: &str| -> bool {
            matcher
                .map(|m| m.could_start_two_word_command(w))
                .unwrap_or(false)
        };

        // Process through buffer (seq is ignored, kept for logging only)
        let items = self.word_buffer.process_word(
            word.to_string(),
            session,
            &single_word_matcher,
            &two_word_matcher,
            &could_start,
        );

        // Process each item
        for item in items {
            self.process_item(item).await?;
        }

        Ok(())
    }

    /// Process a single item from the word buffer.
    async fn process_item(&mut self, item: ProcessedItem) -> Result<()> {
        match item {
            ProcessedItem::Text(text) => {
                // Check recording mode
                if let (Some(state), Some(store)) = (&self.state, &self.voice_command_store) {
                    if let Some(command) = state.get_recording_command() {
                        info!("Recording word '{}' for command '{}'", text.trim(), command);
                        if let Err(e) = store.set_phrase(&command, text.trim()) {
                            error!("Failed to save phrase: {}", e);
                        }
                        state.stop_recording();
                        return Ok(());
                    }
                }

                // Type the text (includes trailing space)
                if let Err(e) = self.injector.type_text(&text) {
                    error!("Failed to inject text: {}", e);
                } else {
                    debug!("Word delivered: '{}' -> typed", text.trim());
                }
            }
            ProcessedItem::Command(cmd_code) => {
                debug!("Executing command from word buffer: {}", cmd_code);
                if let Some(cmd) = VoiceCommand::parse(&cmd_code) {
                    if let Err(e) = crate::commands::execute(&cmd, self.injector.as_ref()) {
                        error!("Failed to execute command: {}", e);
                    } else {
                        debug!("Command delivered: {} -> executed", cmd_code);
                    }
                }
            }
        }
        Ok(())
    }

    /// Flush any pending words that have timed out.
    /// Call this periodically to ensure words aren't stuck in the look-ahead buffer.
    pub fn flush_pending_words(&mut self) -> Vec<ProcessedItem> {
        let matcher = self.matcher.as_ref();
        let single_word_matcher =
            |w: &str| -> Option<String> { matcher.and_then(|m| m.match_single_word(w)) };
        self.word_buffer.flush_pending(&single_word_matcher)
    }

    /// Process all pending flushes and return items ready for processing.
    /// This combines flush_pending and flush_stale for convenience.
    pub async fn process_periodic_flush(&mut self) -> Result<()> {
        // Flush look-ahead pending words (100ms timeout is built into the buffer)
        let pending_items = self.flush_pending_words();
        for item in pending_items {
            self.process_item(item).await?;
        }

        Ok(())
    }
}
