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

//! Word buffer for handling look-ahead for 2-word commands.
//!
//! This module provides a buffer that:
//! - Implements look-ahead for potential 2-word commands
//! - Handles session changes gracefully

use std::time::{Duration, Instant};
use tracing::debug;

/// Default look-ahead timeout for 2-word command matching.
pub const LOOK_AHEAD_TIMEOUT: Duration = Duration::from_millis(100);

/// Result of processing a word.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProcessedItem {
    /// A word to be typed (with trailing space).
    Text(String),
    /// A command to execute.
    Command(String), // Command code like "ENTER", "COPY"
}

/// A word pending for look-ahead (waiting for potential 2nd word of command).
#[derive(Debug, Clone)]
struct PendingWord {
    word: String,
    received_at: Instant,
}

/// Buffer for handling look-ahead for 2-word commands.
///
/// Words arrive from the Android app and are processed immediately.
/// This buffer implements look-ahead for 2-word commands (e.g., "select all").
#[derive(Debug)]
pub struct WordBuffer {
    /// Current session ID.
    current_session: Option<String>,
    /// Pending word for look-ahead (waiting for potential 2nd word of command).
    pending: Option<PendingWord>,
}

impl Default for WordBuffer {
    fn default() -> Self {
        Self::new()
    }
}

impl WordBuffer {
    /// Create a new word buffer.
    pub fn new() -> Self {
        Self {
            current_session: None,
            pending: None,
        }
    }

    /// Reset the buffer state completely.
    ///
    /// Call this when a new connection is established to ensure stale
    /// session state doesn't interfere with the new connection.
    pub fn reset(&mut self) {
        self.current_session = None;
        self.pending = None;
    }

    /// Process an incoming word. Returns items ready to be processed.
    ///
    /// May return empty vec if word is buffered for look-ahead.
    ///
    /// # Arguments
    /// * `word` - The word received
    /// * `session` - Session ID
    /// * `command_matcher` - Function to match single-word commands
    /// * `two_word_matcher` - Function to match two-word commands
    /// * `could_start_two_word` - Function to check if word could be first of 2-word command
    pub fn process_word(
        &mut self,
        word: String,
        session: &str,
        command_matcher: &dyn Fn(&str) -> Option<String>,
        two_word_matcher: &dyn Fn(&str, &str) -> Option<String>,
        could_start_two_word: &dyn Fn(&str) -> bool,
    ) -> Vec<ProcessedItem> {
        // Check for session change
        if self.current_session.as_deref() != Some(session) {
            self.reset_for_session(session);
        }

        let mut results = Vec::new();

        // Process immediately - no sequence buffering!
        self.process_single_word(
            word,
            command_matcher,
            two_word_matcher,
            could_start_two_word,
            &mut results,
        );

        results
    }

    /// Process a single word.
    fn process_single_word(
        &mut self,
        word: String,
        command_matcher: &dyn Fn(&str) -> Option<String>,
        two_word_matcher: &dyn Fn(&str, &str) -> Option<String>,
        could_start_two_word: &dyn Fn(&str) -> bool,
        results: &mut Vec<ProcessedItem>,
    ) {
        // If there's a pending word, try to match 2-word command
        if let Some(pending) = self.pending.take() {
            if let Some(cmd) = two_word_matcher(&pending.word, &word) {
                // Two-word command matched!
                results.push(ProcessedItem::Command(cmd));
                return;
            }

            // No 2-word match - emit pending as text
            results.push(ProcessedItem::Text(format!("{} ", pending.word)));

            // Now process the new word
        }

        // Check if this word could start a 2-word command
        if could_start_two_word(&word) {
            // Buffer for look-ahead
            self.pending = Some(PendingWord {
                word,
                received_at: Instant::now(),
            });
            return;
        }

        // Check for single-word command
        if let Some(cmd) = command_matcher(&word) {
            results.push(ProcessedItem::Command(cmd));
            return;
        }

        // Regular word - emit as text
        results.push(ProcessedItem::Text(format!("{} ", word)));
    }

    /// Flush any pending look-ahead word if timed out.
    ///
    /// Call this periodically (e.g., every 50ms).
    pub fn flush_pending(
        &mut self,
        command_matcher: &dyn Fn(&str) -> Option<String>,
    ) -> Vec<ProcessedItem> {
        let mut results = Vec::new();

        if let Some(ref pending) = self.pending {
            if pending.received_at.elapsed() >= LOOK_AHEAD_TIMEOUT {
                let pending = self.pending.take().unwrap();

                // Check if it's a single-word command
                if let Some(cmd) = command_matcher(&pending.word) {
                    results.push(ProcessedItem::Command(cmd));
                } else {
                    // Emit as text
                    results.push(ProcessedItem::Text(format!("{} ", pending.word)));
                }
            }
        }

        results
    }

    /// Flush stale buffered words (no-op in simplified version).
    ///
    /// Kept for API compatibility.
    pub fn flush_stale(&mut self, _max_age: Duration) -> Vec<ProcessedItem> {
        Vec::new()
    }

    /// Reset state for a new session.
    fn reset_for_session(&mut self, session: &str) {
        debug!(
            "Session changed to '{}', resetting word buffer state",
            session
        );
        self.current_session = Some(session.to_string());

        // Discard any pending word from old session
        self.pending = None;
    }

    /// Check if there's a pending word waiting for look-ahead.
    #[cfg(test)]
    fn has_pending(&self) -> bool {
        self.pending.is_some()
    }

    /// Get the pending word (for testing).
    #[cfg(test)]
    fn pending_word(&self) -> Option<&str> {
        self.pending.as_ref().map(|p| p.word.as_str())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Test helpers
    fn no_command(_word: &str) -> Option<String> {
        None
    }

    fn no_two_word(_w1: &str, _w2: &str) -> Option<String> {
        None
    }

    fn never_starts_two_word(_word: &str) -> bool {
        false
    }

    fn simple_command_matcher(word: &str) -> Option<String> {
        match word.to_lowercase().as_str() {
            "enter" => Some("ENTER".to_string()),
            "copy" => Some("COPY".to_string()),
            "paste" => Some("PASTE".to_string()),
            _ => None,
        }
    }

    fn select_all_matcher(w1: &str, w2: &str) -> Option<String> {
        if w1.to_lowercase() == "select" && w2.to_lowercase() == "all" {
            Some("SELECT_ALL".to_string())
        } else {
            None
        }
    }

    fn could_be_select(word: &str) -> bool {
        word.to_lowercase() == "select"
    }

    #[test]
    fn test_words_processed_immediately() {
        let mut buffer = WordBuffer::new();

        let items = buffer.process_word(
            "hello".to_string(),
            "session1",
            &no_command,
            &no_two_word,
            &never_starts_two_word,
        );
        assert_eq!(items, vec![ProcessedItem::Text("hello ".to_string())]);

        let items = buffer.process_word(
            "world".to_string(),
            "session1",
            &no_command,
            &no_two_word,
            &never_starts_two_word,
        );
        assert_eq!(items, vec![ProcessedItem::Text("world ".to_string())]);
    }

    #[test]
    fn test_session_change_resets_state() {
        let mut buffer = WordBuffer::new();

        // Process some words in session 1
        buffer.process_word(
            "hello".to_string(),
            "session1",
            &no_command,
            &no_two_word,
            &never_starts_two_word,
        );

        // New session should work fine
        let items = buffer.process_word(
            "new".to_string(),
            "session2",
            &no_command,
            &no_two_word,
            &never_starts_two_word,
        );
        assert_eq!(
            items,
            vec![ProcessedItem::Text("new ".to_string())],
            "new session should process words"
        );
    }

    #[test]
    fn test_session_change_clears_pending() {
        let mut buffer = WordBuffer::new();

        // Buffer "select" as pending (could start 2-word command)
        let items = buffer.process_word(
            "select".to_string(),
            "session1",
            &simple_command_matcher,
            &select_all_matcher,
            &could_be_select,
        );
        assert!(items.is_empty(), "select should be buffered as pending");
        assert!(buffer.has_pending());

        // New session should clear pending
        let items = buffer.process_word(
            "hello".to_string(),
            "session2",
            &simple_command_matcher,
            &select_all_matcher,
            &could_be_select,
        );
        assert_eq!(items, vec![ProcessedItem::Text("hello ".to_string())]);
        assert!(!buffer.has_pending());
    }

    #[test]
    fn test_single_word_command_matching() {
        let mut buffer = WordBuffer::new();

        let items = buffer.process_word(
            "enter".to_string(),
            "session1",
            &simple_command_matcher,
            &no_two_word,
            &never_starts_two_word,
        );
        assert_eq!(items, vec![ProcessedItem::Command("ENTER".to_string())]);

        let items = buffer.process_word(
            "copy".to_string(),
            "session1",
            &simple_command_matcher,
            &no_two_word,
            &never_starts_two_word,
        );
        assert_eq!(items, vec![ProcessedItem::Command("COPY".to_string())]);
    }

    #[test]
    fn test_two_word_command_matching() {
        let mut buffer = WordBuffer::new();

        // "select" should be buffered for look-ahead
        let items = buffer.process_word(
            "select".to_string(),
            "session1",
            &simple_command_matcher,
            &select_all_matcher,
            &could_be_select,
        );
        assert!(items.is_empty(), "select should be buffered");
        assert_eq!(buffer.pending_word(), Some("select"));

        // "all" should complete the command
        let items = buffer.process_word(
            "all".to_string(),
            "session1",
            &simple_command_matcher,
            &select_all_matcher,
            &could_be_select,
        );
        assert_eq!(
            items,
            vec![ProcessedItem::Command("SELECT_ALL".to_string())]
        );
        assert!(!buffer.has_pending());
    }

    #[test]
    fn test_look_ahead_no_match_emits_pending() {
        let mut buffer = WordBuffer::new();

        // "select" should be buffered
        let items = buffer.process_word(
            "select".to_string(),
            "session1",
            &simple_command_matcher,
            &select_all_matcher,
            &could_be_select,
        );
        assert!(items.is_empty());

        // "something" doesn't complete 2-word command
        let items = buffer.process_word(
            "something".to_string(),
            "session1",
            &simple_command_matcher,
            &select_all_matcher,
            &could_be_select,
        );
        assert_eq!(
            items,
            vec![
                ProcessedItem::Text("select ".to_string()),
                ProcessedItem::Text("something ".to_string()),
            ]
        );
    }

    #[test]
    fn test_flush_pending_after_timeout() {
        let mut buffer = WordBuffer::new();

        // Buffer "select"
        buffer.process_word(
            "select".to_string(),
            "session1",
            &simple_command_matcher,
            &select_all_matcher,
            &could_be_select,
        );
        assert!(buffer.has_pending());

        // Immediately flush - should return empty (not timed out yet)
        let items = buffer.flush_pending(&simple_command_matcher);
        assert!(items.is_empty(), "should not flush before timeout");

        // Simulate timeout by replacing pending with an old timestamp
        if let Some(ref mut pending) = buffer.pending {
            pending.received_at = Instant::now() - Duration::from_millis(200);
        }

        // Now flush should emit the pending word as text
        let items = buffer.flush_pending(&simple_command_matcher);
        assert_eq!(items, vec![ProcessedItem::Text("select ".to_string())]);
        assert!(!buffer.has_pending());
    }

    #[test]
    fn test_flush_pending_single_word_command() {
        let mut buffer = WordBuffer::new();

        // Create a matcher where "select" is ALSO a single-word command
        let select_is_command = |word: &str| -> Option<String> {
            if word.to_lowercase() == "select" {
                Some("SELECT".to_string())
            } else {
                None
            }
        };

        // Buffer "select"
        buffer.process_word(
            "select".to_string(),
            "session1",
            &select_is_command,
            &select_all_matcher,
            &could_be_select,
        );

        // Simulate timeout
        if let Some(ref mut pending) = buffer.pending {
            pending.received_at = Instant::now() - Duration::from_millis(200);
        }

        // Flush should emit as command (since it matches single-word)
        let items = buffer.flush_pending(&select_is_command);
        assert_eq!(items, vec![ProcessedItem::Command("SELECT".to_string())]);
    }

    #[test]
    fn test_mixed_text_and_commands() {
        let mut buffer = WordBuffer::new();

        // Send: "hello", "enter", "world"
        let mut all_items = Vec::new();

        all_items.extend(buffer.process_word(
            "hello".to_string(),
            "session1",
            &simple_command_matcher,
            &no_two_word,
            &never_starts_two_word,
        ));
        all_items.extend(buffer.process_word(
            "enter".to_string(),
            "session1",
            &simple_command_matcher,
            &no_two_word,
            &never_starts_two_word,
        ));
        all_items.extend(buffer.process_word(
            "world".to_string(),
            "session1",
            &simple_command_matcher,
            &no_two_word,
            &never_starts_two_word,
        ));

        assert_eq!(
            all_items,
            vec![
                ProcessedItem::Text("hello ".to_string()),
                ProcessedItem::Command("ENTER".to_string()),
                ProcessedItem::Text("world ".to_string()),
            ]
        );
    }

    #[test]
    fn test_reset_clears_all_state() {
        let mut buffer = WordBuffer::new();

        // Process several words in session1
        buffer.process_word(
            "hello".to_string(),
            "session1",
            &no_command,
            &no_two_word,
            &never_starts_two_word,
        );
        buffer.process_word(
            "world".to_string(),
            "session1",
            &no_command,
            &no_two_word,
            &never_starts_two_word,
        );

        // Reset the buffer (simulating reconnection)
        buffer.reset();

        // Now process words with SAME session ID
        let items = buffer.process_word(
            "reconnected".to_string(),
            "session1",
            &no_command,
            &no_two_word,
            &never_starts_two_word,
        );

        // Should work because reset() cleared the state
        assert_eq!(
            items,
            vec![ProcessedItem::Text("reconnected ".to_string())],
            "after reset, words should be processed"
        );
    }

    #[test]
    fn test_flush_stale_is_noop() {
        let mut buffer = WordBuffer::new();

        // flush_stale should always return empty (no-op in simplified version)
        let items = buffer.flush_stale(Duration::from_millis(100));
        assert!(items.is_empty());
    }
}
