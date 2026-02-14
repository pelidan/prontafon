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

//! Command matcher for voice phrase recognition.
//!
//! Matches spoken phrases to commands using the voice command store.
//! Supports both exact matching (entire input) and mid-text matching
//! (finding command words within longer text).

use std::sync::Arc;
use tracing::debug;

use super::VoiceCommand;
use crate::storage::VoiceCommandStore;

/// Default 2-word command phrases
pub const DEFAULT_TWO_WORD_PHRASES: &[(&str, &str)] =
    &[("select all", "SELECT_ALL"), ("new line", "ENTER")];

/// Result of matching input text that may contain commands.
#[derive(Debug, Clone)]
pub enum MatchResult {
    /// Entire input is a command (no text to type).
    ExactCommand(VoiceCommand),
    /// No commands found - type the entire input.
    NoMatch,
    /// Command found within text - returns segments to process in order.
    /// Each segment is either text to type or a command to execute.
    MidTextCommand(Vec<TextSegment>),
}

/// A segment of text that is either literal text or a command.
#[derive(Debug, Clone)]
pub enum TextSegment {
    /// Text that should be typed as-is.
    Text(String),
    /// A command that should be executed.
    Command(VoiceCommand),
}

/// Combined matcher that checks custom phrases first, then built-in commands.
/// Supports finding commands anywhere in the input text.
pub struct CombinedMatcher {
    store: Arc<VoiceCommandStore>,
}

impl CombinedMatcher {
    /// Create a new combined matcher.
    pub fn new(store: Arc<VoiceCommandStore>) -> Self {
        Self { store }
    }

    /// Try to match as a spoken phrase first, then as a command code.
    /// This is the original exact-match method for backward compatibility.
    pub fn match_input(&self, input: &str) -> Option<VoiceCommand> {
        // First, try to match as a spoken phrase (custom or default)
        if let Some(command_code) = self.store.match_phrase(input) {
            debug!(
                "Matched spoken phrase '{}' to command '{}'",
                input, command_code
            );
            return VoiceCommand::parse(&command_code);
        }

        // Fall back to direct command code parsing (for protocol messages)
        VoiceCommand::parse(input)
    }

    /// Match input that may contain commands anywhere in the text.
    ///
    /// This handles scenarios where a command word appears in the middle of speech:
    /// - "hello world šmach and more" → ["hello world ", ENTER, "and more"]
    ///
    /// Returns a MatchResult indicating how the input should be processed.
    ///
    /// Commands ALWAYS execute when recognized - they are never typed as text.
    /// The trailing space from Android word-by-word sending is irrelevant;
    /// if a word matches a command, it executes.
    pub fn match_with_context(&self, input: &str) -> MatchResult {
        // Try exact match first (handles single command words with or without trailing space)
        // Trim for matching but preserve original for mid-text processing
        let trimmed = input.trim();
        if let Some(cmd) = self.match_input(trimmed) {
            return MatchResult::ExactCommand(cmd);
        }

        // Split input into words while preserving spacing
        // We need to match individual words against command phrases
        let words: Vec<&str> = input.split_whitespace().collect();
        if words.is_empty() {
            return MatchResult::NoMatch;
        }

        // Check each word to see if it matches a command phrase
        let mut segments: Vec<TextSegment> = Vec::new();
        let mut text_buffer = String::new();
        let mut found_command = false;

        // Track position in original input to preserve spacing
        let mut last_end = 0;

        for word in &words {
            // Find this word's position in the original input
            let word_start = input[last_end..].find(word).map(|i| last_end + i);
            if word_start.is_none() {
                continue;
            }
            let word_start = word_start.unwrap();
            let word_end = word_start + word.len();

            // Check if this word matches a command
            if let Some(command_code) = self.store.match_phrase(word) {
                if let Some(cmd) = VoiceCommand::parse(&command_code) {
                    // Found a command! First, flush any accumulated text (including leading whitespace)
                    if word_start > last_end {
                        text_buffer.push_str(&input[last_end..word_start]);
                    }
                    if !text_buffer.is_empty() {
                        segments.push(TextSegment::Text(text_buffer.clone()));
                        text_buffer.clear();
                    }

                    // Add the command
                    debug!("Found command word '{}' → {:?} in text", word, cmd);
                    segments.push(TextSegment::Command(cmd));
                    found_command = true;

                    last_end = word_end;
                    continue;
                }
            }

            // Not a command - add to text buffer (including any whitespace before it)
            text_buffer.push_str(&input[last_end..word_end]);
            last_end = word_end;
        }

        // Add any remaining text after the last word
        if last_end < input.len() {
            text_buffer.push_str(&input[last_end..]);
        }

        // Flush remaining text buffer
        if !text_buffer.is_empty() {
            segments.push(TextSegment::Text(text_buffer));
        }

        if found_command {
            MatchResult::MidTextCommand(segments)
        } else {
            MatchResult::NoMatch
        }
    }

    /// Match a single word against command phrases.
    /// Returns the command code (e.g., "ENTER") if matched.
    pub fn match_single_word(&self, word: &str) -> Option<String> {
        let normalized = Self::normalize_for_matching(word);
        // Check custom phrases first, then defaults
        self.store.match_phrase(&normalized)
    }

    /// Match two words as a potential 2-word command.
    /// Returns the command code if the two words form a command.
    pub fn match_two_words(&self, word1: &str, word2: &str) -> Option<String> {
        let phrase = format!(
            "{} {}",
            Self::normalize_for_matching(word1),
            Self::normalize_for_matching(word2)
        );

        // Check custom 2-word phrases first
        if let Some(cmd) = self.store.match_phrase(&phrase) {
            return Some(cmd);
        }

        // Check default 2-word phrases
        for (default_phrase, command) in DEFAULT_TWO_WORD_PHRASES {
            if phrase == *default_phrase {
                return Some(command.to_string());
            }
        }

        None
    }

    /// Check if a word could be the first word of a 2-word command.
    /// Used for look-ahead buffering.
    pub fn could_start_two_word_command(&self, word: &str) -> bool {
        let normalized = Self::normalize_for_matching(word);

        // Check default 2-word phrases
        for (phrase, _) in DEFAULT_TWO_WORD_PHRASES {
            if phrase.starts_with(&normalized) && phrase.contains(' ') {
                let first_word = phrase.split_whitespace().next().unwrap_or("");
                if first_word == normalized {
                    return true;
                }
            }
        }

        // Check custom phrases that have 2 words
        // (Note: may need to add method to store to get all phrases)
        self.store.could_start_two_word_command(&normalized)
    }

    /// Normalize a word for matching: trim, lowercase, strip punctuation
    fn normalize_for_matching(word: &str) -> String {
        word.trim()
            .trim_end_matches(['.', ',', '!', '?', ':', ';'])
            .to_lowercase()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn create_test_store_with_custom_phrase(
        phrase: &str,
        command: &str,
    ) -> (TempDir, Arc<VoiceCommandStore>) {
        let temp_dir = TempDir::new().unwrap();
        let store = VoiceCommandStore::new(temp_dir.path()).unwrap();
        store.set_phrase(command, phrase).unwrap();
        (temp_dir, Arc::new(store))
    }

    #[test]
    fn test_exact_match_custom_phrase() {
        let (_temp, store) = create_test_store_with_custom_phrase("šmach", "ENTER");
        let matcher = CombinedMatcher::new(store);

        // Should match the exact phrase
        let result = matcher.match_with_context("šmach");
        match result {
            MatchResult::ExactCommand(cmd) => assert_eq!(cmd, VoiceCommand::Enter),
            _ => panic!("Expected ExactCommand"),
        }
    }

    #[test]
    fn test_single_word_with_trailing_space_is_still_command() {
        // Commands ALWAYS execute, even with trailing space from Android word-by-word sending
        let (_temp, store) = create_test_store_with_custom_phrase("šmach", "ENTER");
        let matcher = CombinedMatcher::new(store);

        // Single word with trailing space should STILL be recognized as command
        let result = matcher.match_with_context("šmach ");
        match result {
            MatchResult::ExactCommand(cmd) => assert_eq!(cmd, VoiceCommand::Enter),
            _ => panic!(
                "Expected ExactCommand - commands always execute regardless of trailing space"
            ),
        }
    }

    #[test]
    fn test_command_in_multi_word_phrase_with_trailing_space() {
        // Multi-word phrases are checked for commands (mid-text matching)
        let (_temp, store) = create_test_store_with_custom_phrase("šmach", "ENTER");
        let matcher = CombinedMatcher::new(store);

        // "hello šmach " = should find šmach as mid-text command
        let result = matcher.match_with_context("hello šmach ");
        match result {
            MatchResult::MidTextCommand(segments) => {
                assert_eq!(segments.len(), 3);
                match &segments[0] {
                    TextSegment::Text(text) => assert!(text.contains("hello")),
                    _ => panic!("Expected Text at position 0"),
                }
                match &segments[1] {
                    TextSegment::Command(cmd) => assert_eq!(*cmd, VoiceCommand::Enter),
                    _ => panic!("Expected Command at position 1"),
                }
                match &segments[2] {
                    TextSegment::Text(text) => assert_eq!(text, " "),
                    _ => panic!("Expected trailing space Text at position 2"),
                }
            }
            _ => panic!("Expected MidTextCommand"),
        }
    }

    #[test]
    fn test_no_match() {
        let (_temp, store) = create_test_store_with_custom_phrase("šmach", "ENTER");
        let matcher = CombinedMatcher::new(store);

        let result = matcher.match_with_context("hello world");
        match result {
            MatchResult::NoMatch => {}
            _ => panic!("Expected NoMatch"),
        }
    }

    #[test]
    fn test_mid_text_command_at_start() {
        let (_temp, store) = create_test_store_with_custom_phrase("šmach", "ENTER");
        let matcher = CombinedMatcher::new(store);

        let result = matcher.match_with_context("šmach and more text");
        match result {
            MatchResult::MidTextCommand(segments) => {
                assert_eq!(segments.len(), 2);
                match &segments[0] {
                    TextSegment::Command(cmd) => assert_eq!(*cmd, VoiceCommand::Enter),
                    _ => panic!("Expected Command at position 0"),
                }
                match &segments[1] {
                    TextSegment::Text(text) => assert!(text.contains("and more text")),
                    _ => panic!("Expected Text at position 1"),
                }
            }
            _ => panic!("Expected MidTextCommand"),
        }
    }

    #[test]
    fn test_mid_text_command_in_middle() {
        let (_temp, store) = create_test_store_with_custom_phrase("šmach", "ENTER");
        let matcher = CombinedMatcher::new(store);

        let result = matcher.match_with_context("hello world šmach and more");
        match result {
            MatchResult::MidTextCommand(segments) => {
                assert_eq!(segments.len(), 3);
                match &segments[0] {
                    TextSegment::Text(text) => assert!(text.contains("hello world")),
                    _ => panic!("Expected Text at position 0"),
                }
                match &segments[1] {
                    TextSegment::Command(cmd) => assert_eq!(*cmd, VoiceCommand::Enter),
                    _ => panic!("Expected Command at position 1"),
                }
                match &segments[2] {
                    TextSegment::Text(text) => assert!(text.contains("and more")),
                    _ => panic!("Expected Text at position 2"),
                }
            }
            _ => panic!("Expected MidTextCommand"),
        }
    }

    #[test]
    fn test_mid_text_command_at_end() {
        let (_temp, store) = create_test_store_with_custom_phrase("šmach", "ENTER");
        let matcher = CombinedMatcher::new(store);

        let result = matcher.match_with_context("hello world šmach");
        match result {
            MatchResult::MidTextCommand(segments) => {
                assert_eq!(segments.len(), 2);
                match &segments[0] {
                    TextSegment::Text(text) => assert!(text.contains("hello world")),
                    _ => panic!("Expected Text at position 0"),
                }
                match &segments[1] {
                    TextSegment::Command(cmd) => assert_eq!(*cmd, VoiceCommand::Enter),
                    _ => panic!("Expected Command at position 1"),
                }
            }
            _ => panic!("Expected MidTextCommand"),
        }
    }

    #[test]
    fn test_default_phrase_enter() {
        let temp_dir = TempDir::new().unwrap();
        let store = Arc::new(VoiceCommandStore::new(temp_dir.path()).unwrap());
        let matcher = CombinedMatcher::new(store);

        // "enter" is a deliberate command
        let result = matcher.match_with_context("enter");
        match result {
            MatchResult::ExactCommand(cmd) => assert_eq!(cmd, VoiceCommand::Enter),
            _ => panic!("Expected ExactCommand for default 'enter' phrase"),
        }

        // "enter " with trailing space should STILL be a command
        let result = matcher.match_with_context("enter ");
        match result {
            MatchResult::ExactCommand(cmd) => assert_eq!(cmd, VoiceCommand::Enter),
            _ => panic!("Expected ExactCommand for 'enter ' - commands always execute"),
        }
    }

    #[test]
    fn test_match_single_word_custom_phrase() {
        let (_temp, store) = create_test_store_with_custom_phrase("šmach", "ENTER");
        let matcher = CombinedMatcher::new(store);

        // Should match custom phrase
        assert_eq!(
            matcher.match_single_word("šmach"),
            Some("ENTER".to_string())
        );

        // Should not match unknown word
        assert_eq!(matcher.match_single_word("unknown"), None);
    }

    #[test]
    fn test_match_single_word_default_phrase() {
        let temp_dir = TempDir::new().unwrap();
        let store = Arc::new(VoiceCommandStore::new(temp_dir.path()).unwrap());
        let matcher = CombinedMatcher::new(store);

        // Should match default "enter" phrase
        assert_eq!(
            matcher.match_single_word("enter"),
            Some("ENTER".to_string())
        );

        // Should match default "copy" phrase
        assert_eq!(matcher.match_single_word("copy"), Some("COPY".to_string()));
    }

    #[test]
    fn test_match_two_words_select_all() {
        let temp_dir = TempDir::new().unwrap();
        let store = Arc::new(VoiceCommandStore::new(temp_dir.path()).unwrap());
        let matcher = CombinedMatcher::new(store);

        // "select all" should match SELECT_ALL
        assert_eq!(
            matcher.match_two_words("select", "all"),
            Some("SELECT_ALL".to_string())
        );
    }

    #[test]
    fn test_match_two_words_new_line() {
        let temp_dir = TempDir::new().unwrap();
        let store = Arc::new(VoiceCommandStore::new(temp_dir.path()).unwrap());
        let matcher = CombinedMatcher::new(store);

        // "new line" should match ENTER
        assert_eq!(
            matcher.match_two_words("new", "line"),
            Some("ENTER".to_string())
        );
    }

    #[test]
    fn test_match_two_words_no_match() {
        let temp_dir = TempDir::new().unwrap();
        let store = Arc::new(VoiceCommandStore::new(temp_dir.path()).unwrap());
        let matcher = CombinedMatcher::new(store);

        // Random words should not match
        assert_eq!(matcher.match_two_words("hello", "world"), None);
    }

    #[test]
    fn test_could_start_two_word_command() {
        let temp_dir = TempDir::new().unwrap();
        let store = Arc::new(VoiceCommandStore::new(temp_dir.path()).unwrap());
        let matcher = CombinedMatcher::new(store);

        // "select" starts "select all"
        assert!(matcher.could_start_two_word_command("select"));

        // "new" starts "new line"
        assert!(matcher.could_start_two_word_command("new"));

        // "hello" doesn't start any 2-word command
        assert!(!matcher.could_start_two_word_command("hello"));

        // "all" doesn't start a 2-word command (it's the second word)
        assert!(!matcher.could_start_two_word_command("all"));
    }

    #[test]
    fn test_punctuation_stripping() {
        let (_temp, store) = create_test_store_with_custom_phrase("hello", "ENTER");
        let matcher = CombinedMatcher::new(store);

        // "hello." should match "hello" (punctuation stripped)
        assert_eq!(
            matcher.match_single_word("hello."),
            Some("ENTER".to_string())
        );

        // "hello," should match "hello"
        assert_eq!(
            matcher.match_single_word("hello,"),
            Some("ENTER".to_string())
        );

        // "hello!" should match "hello"
        assert_eq!(
            matcher.match_single_word("hello!"),
            Some("ENTER".to_string())
        );

        // "hello?" should match "hello"
        assert_eq!(
            matcher.match_single_word("hello?"),
            Some("ENTER".to_string())
        );

        // "hello:" should match "hello"
        assert_eq!(
            matcher.match_single_word("hello:"),
            Some("ENTER".to_string())
        );

        // "hello;" should match "hello"
        assert_eq!(
            matcher.match_single_word("hello;"),
            Some("ENTER".to_string())
        );
    }

    #[test]
    fn test_two_words_with_punctuation() {
        let temp_dir = TempDir::new().unwrap();
        let store = Arc::new(VoiceCommandStore::new(temp_dir.path()).unwrap());
        let matcher = CombinedMatcher::new(store);

        // "select all." should match (punctuation stripped from second word)
        assert_eq!(
            matcher.match_two_words("select", "all."),
            Some("SELECT_ALL".to_string())
        );

        // "new line!" should match
        assert_eq!(
            matcher.match_two_words("new", "line!"),
            Some("ENTER".to_string())
        );
    }
}
