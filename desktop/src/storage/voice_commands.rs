// Copyright 2024 Prontafon Contributors
// SPDX-License-Identifier: Apache-2.0

//! Voice command mapping storage.
//!
//! Handles loading, saving, and watching for changes to custom voice command mappings.

use anyhow::{Context, Result};
use chrono::{DateTime, Utc};
use notify::{Config, Event, RecommendedWatcher, RecursiveMode, Watcher};
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;
use tracing::{debug, error, info, warn};

/// Normalize a phrase to at most 2 words.
/// If more than 2 words, take the last 2.
/// Trims whitespace and converts to lowercase.
fn normalize_phrase(phrase: &str) -> String {
    let words: Vec<&str> = phrase.split_whitespace().collect();
    match words.len() {
        0 => String::new(),
        1 => words[0].to_lowercase(),
        2 => format!("{} {}", words[0].to_lowercase(), words[1].to_lowercase()),
        _ => {
            // Take last 2 words
            let last_two = &words[words.len() - 2..];
            format!(
                "{} {}",
                last_two[0].to_lowercase(),
                last_two[1].to_lowercase()
            )
        }
    }
}

/// Default phrases for built-in commands (case-insensitive matching).
pub const DEFAULT_PHRASES: &[(&str, &str)] = &[
    ("ENTER", "enter"),
    ("SELECT_ALL", "select all"),
    ("COPY", "copy"),
    ("PASTE", "paste"),
    ("CUT", "cut"),
    ("CANCEL", "cancel"),
];

/// A single voice command mapping.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VoiceCommandMapping {
    /// The spoken phrase (case-insensitive).
    pub phrase: String,
    /// The command code to execute (ENTER, COPY, etc.).
    pub command: String,
    /// When this mapping was created.
    pub created_at: DateTime<Utc>,
}

impl VoiceCommandMapping {
    /// Create a new mapping.
    pub fn new(phrase: impl Into<String>, command: impl Into<String>) -> Self {
        Self {
            phrase: phrase.into(),
            command: command.into(),
            created_at: Utc::now(),
        }
    }
}

/// Voice commands file format.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VoiceCommandsFile {
    /// File format version.
    pub version: u32,
    /// Custom command mappings (only overrides are stored).
    pub mappings: Vec<VoiceCommandMapping>,
}

impl Default for VoiceCommandsFile {
    fn default() -> Self {
        Self {
            version: 1,
            mappings: Vec::new(),
        }
    }
}

/// Represents a command with its current phrase configuration.
#[derive(Debug, Clone)]
pub struct CommandInfo {
    /// The command code (ENTER, COPY, etc.).
    pub command: String,
    /// The current phrase (custom or default).
    pub phrase: String,
    /// Whether this is a custom phrase (true) or default (false).
    pub is_custom: bool,
    /// The default phrase for this command.
    #[allow(dead_code)]
    pub default_phrase: String,
}

/// Voice command store with file watching.
pub struct VoiceCommandStore {
    /// Path to the voice_commands.json file.
    config_path: PathBuf,
    /// Current mappings indexed by command code.
    mappings: Arc<RwLock<HashMap<String, VoiceCommandMapping>>>,
    /// File watcher (kept alive).
    _watcher: Option<RecommendedWatcher>,
}

impl VoiceCommandStore {
    /// Create a new store without file watching.
    #[allow(dead_code)]
    pub fn new(config_dir: &Path) -> Result<Self> {
        let config_path = config_dir.join("voice_commands.json");

        let mut store = Self {
            config_path,
            mappings: Arc::new(RwLock::new(HashMap::new())),
            _watcher: None,
        };

        // Load existing mappings
        store.load()?;

        Ok(store)
    }

    /// Create a new store with file watching enabled.
    pub fn new_with_watcher(config_dir: &Path) -> Result<Self> {
        let config_path = config_dir.join("voice_commands.json");
        let mappings = Arc::new(RwLock::new(HashMap::new()));

        // Ensure config directory exists
        std::fs::create_dir_all(config_dir)?;

        // Set up file watcher
        let config_path_watch = config_path.clone();
        let mappings_watch = mappings.clone();

        let mut watcher = RecommendedWatcher::new(
            move |res: Result<Event, notify::Error>| {
                match res {
                    Ok(event) => {
                        if event.kind.is_modify() || event.kind.is_create() {
                            debug!("Voice commands file changed, reloading...");
                            // Reload in the watcher thread
                            if let Ok(new_mappings) = Self::load_from_file(&config_path_watch) {
                                let mut guard = mappings_watch.write();
                                *guard = new_mappings;
                                info!("Voice commands reloaded: {} custom mappings", guard.len());
                            }
                        }
                    }
                    Err(e) => {
                        error!("File watcher error: {}", e);
                    }
                }
            },
            Config::default().with_poll_interval(Duration::from_millis(500)),
        )?;

        // Watch the config directory
        watcher.watch(config_dir, RecursiveMode::NonRecursive)?;
        info!("Watching {:?} for voice command changes", config_dir);

        let mut store = Self {
            config_path,
            mappings,
            _watcher: Some(watcher),
        };

        // Load existing mappings
        store.load()?;

        Ok(store)
    }

    /// Load mappings from file.
    pub fn load(&mut self) -> Result<()> {
        let new_mappings = Self::load_from_file(&self.config_path)?;
        let mut guard = self.mappings.write();
        *guard = new_mappings;
        info!("Loaded {} custom voice command mappings", guard.len());
        Ok(())
    }

    /// Load mappings from a specific file path.
    fn load_from_file(path: &Path) -> Result<HashMap<String, VoiceCommandMapping>> {
        if !path.exists() {
            debug!("Voice commands file doesn't exist, using defaults");
            return Ok(HashMap::new());
        }

        let content =
            std::fs::read_to_string(path).with_context(|| format!("Failed to read {:?}", path))?;

        let file: VoiceCommandsFile = serde_json::from_str(&content)
            .with_context(|| "Failed to parse voice_commands.json")?;

        // Index by command code, validating/normalizing phrases
        let mut mappings = HashMap::new();
        for mut mapping in file.mappings {
            let command_upper = mapping.command.to_uppercase();

            // Validate and normalize phrase to at most 2 words
            let phrase_trimmed = mapping.phrase.trim();
            if phrase_trimmed.is_empty() {
                warn!(
                    "Skipping command '{}' with empty phrase in config file",
                    command_upper
                );
                continue;
            }

            let word_count = phrase_trimmed.split_whitespace().count();
            let normalized = normalize_phrase(phrase_trimmed);

            if word_count > 2 {
                warn!(
                    "Config file has >2 word phrase for '{}', using last 2: '{}' (from '{}')",
                    command_upper, normalized, phrase_trimmed
                );
            }

            mapping.phrase = normalized;
            mappings.insert(command_upper, mapping);
        }

        Ok(mappings)
    }

    /// Save current mappings to file.
    pub fn save(&self) -> Result<()> {
        let guard = self.mappings.read();

        let file = VoiceCommandsFile {
            version: 1,
            mappings: guard.values().cloned().collect(),
        };

        // Ensure parent directory exists
        if let Some(parent) = self.config_path.parent() {
            std::fs::create_dir_all(parent)?;
        }

        let content = serde_json::to_string_pretty(&file)?;
        std::fs::write(&self.config_path, content)?;

        info!("Saved {} custom voice command mappings", guard.len());
        Ok(())
    }

    /// Get the phrase for a command (custom or default).
    #[allow(dead_code)]
    pub fn get_phrase(&self, command: &str) -> String {
        let command_upper = command.to_uppercase();
        let guard = self.mappings.read();

        if let Some(mapping) = guard.get(&command_upper) {
            return mapping.phrase.clone();
        }

        // Return default phrase
        DEFAULT_PHRASES
            .iter()
            .find(|(cmd, _)| *cmd == command_upper)
            .map(|(_, phrase)| phrase.to_string())
            .unwrap_or_else(|| command.to_lowercase())
    }

    /// Check if a command has a custom phrase.
    #[allow(dead_code)]
    pub fn has_custom_phrase(&self, command: &str) -> bool {
        let command_upper = command.to_uppercase();
        self.mappings.read().contains_key(&command_upper)
    }

    /// Set a custom phrase for a command.
    ///
    /// Custom phrases support 1 or 2 words for reliable matching.
    /// If more than 2 words are provided, the last 2 words are used.
    pub fn set_phrase(&self, command: &str, phrase: &str) -> Result<()> {
        let command_upper = command.to_uppercase();

        // Normalize to at most 2 words
        let final_phrase = normalize_phrase(phrase);

        // Validate: must not be empty
        if final_phrase.is_empty() {
            anyhow::bail!("Custom phrase cannot be empty");
        }

        // Log warning if more than 2 words were provided
        let word_count = phrase.split_whitespace().count();
        if word_count > 2 {
            warn!(
                "More than 2 words detected for command '{}', using last 2: '{}' (from '{}')",
                command_upper,
                final_phrase,
                phrase.trim()
            );
        }

        let mapping = VoiceCommandMapping::new(final_phrase, command_upper.clone());

        {
            let mut guard = self.mappings.write();
            guard.insert(command_upper, mapping);
        }

        self.save()
    }

    /// Remove custom phrase (revert to default).
    pub fn revert_to_default(&self, command: &str) -> Result<()> {
        let command_upper = command.to_uppercase();

        {
            let mut guard = self.mappings.write();
            guard.remove(&command_upper);
        }

        self.save()
    }

    /// Get info for all commands (built-in + custom).
    pub fn get_all_commands(&self) -> Vec<CommandInfo> {
        let guard = self.mappings.read();

        DEFAULT_PHRASES
            .iter()
            .map(|(cmd, default_phrase)| {
                let custom = guard.get(*cmd);
                CommandInfo {
                    command: cmd.to_string(),
                    phrase: custom
                        .map(|m| m.phrase.clone())
                        .unwrap_or_else(|| default_phrase.to_string()),
                    is_custom: custom.is_some(),
                    default_phrase: default_phrase.to_string(),
                }
            })
            .collect()
    }

    /// Match a spoken phrase to a command code.
    /// Returns the command code if found.
    pub fn match_phrase(&self, spoken: &str) -> Option<String> {
        let spoken_lower = spoken.trim().to_lowercase();
        let guard = self.mappings.read();

        // Check custom mappings first
        for (cmd, mapping) in guard.iter() {
            // Trim the stored phrase as well to handle phrases recorded with trailing whitespace
            if mapping.phrase.trim().to_lowercase() == spoken_lower {
                return Some(cmd.clone());
            }
        }

        // Check default phrases (only for commands without custom mappings)
        for (cmd, default_phrase) in DEFAULT_PHRASES {
            if !guard.contains_key(*cmd) && *default_phrase == spoken_lower {
                return Some(cmd.to_string());
            }
        }

        None
    }

    /// Check if a word could be the first word of a custom 2-word command.
    /// Used for look-ahead buffering in command matching.
    pub fn could_start_two_word_command(&self, word: &str) -> bool {
        let word_lower = word.to_lowercase();
        let guard = self.mappings.read();

        // Check custom mappings for 2-word phrases
        for mapping in guard.values() {
            let phrase = mapping.phrase.trim().to_lowercase();
            if phrase.contains(' ') {
                let first_word = phrase.split_whitespace().next().unwrap_or("");
                if first_word == word_lower {
                    return true;
                }
            }
        }

        false
    }

    /// Get the config file path.
    pub fn config_path(&self) -> &Path {
        &self.config_path
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn test_normalize_phrase_empty() {
        assert_eq!(normalize_phrase(""), "");
        assert_eq!(normalize_phrase("   "), "");
    }

    #[test]
    fn test_normalize_phrase_single_word() {
        assert_eq!(normalize_phrase("hello"), "hello");
        assert_eq!(normalize_phrase("HELLO"), "hello");
        assert_eq!(normalize_phrase("  hello  "), "hello");
    }

    #[test]
    fn test_normalize_phrase_two_words() {
        assert_eq!(normalize_phrase("hello world"), "hello world");
        assert_eq!(normalize_phrase("HELLO WORLD"), "hello world");
        assert_eq!(normalize_phrase("  hello   world  "), "hello world");
    }

    #[test]
    fn test_normalize_phrase_more_than_two_words() {
        // Takes last 2 words
        assert_eq!(normalize_phrase("one two three"), "two three");
        assert_eq!(normalize_phrase("a b c d"), "c d");
        assert_eq!(normalize_phrase("please do enter"), "do enter");
    }

    #[test]
    fn test_set_phrase_single_word() -> Result<()> {
        let temp_dir = TempDir::new()?;
        let store = VoiceCommandStore::new(temp_dir.path())?;

        store.set_phrase("ENTER", "submit")?;
        assert_eq!(store.get_phrase("ENTER"), "submit");

        Ok(())
    }

    #[test]
    fn test_set_phrase_two_words() -> Result<()> {
        let temp_dir = TempDir::new()?;
        let store = VoiceCommandStore::new(temp_dir.path())?;

        store.set_phrase("ENTER", "do enter")?;
        assert_eq!(store.get_phrase("ENTER"), "do enter");

        Ok(())
    }

    #[test]
    fn test_set_phrase_more_than_two_words_takes_last_two() -> Result<()> {
        let temp_dir = TempDir::new()?;
        let store = VoiceCommandStore::new(temp_dir.path())?;

        store.set_phrase("ENTER", "please do enter")?;
        // Should take last 2 words
        assert_eq!(store.get_phrase("ENTER"), "do enter");

        Ok(())
    }

    #[test]
    fn test_could_start_two_word_command_no_matches() -> Result<()> {
        let temp_dir = TempDir::new()?;
        let store = VoiceCommandStore::new(temp_dir.path())?;

        // No custom commands yet
        assert!(!store.could_start_two_word_command("do"));
        assert!(!store.could_start_two_word_command("hello"));

        Ok(())
    }

    #[test]
    fn test_could_start_two_word_command_single_word_phrase() -> Result<()> {
        let temp_dir = TempDir::new()?;
        let store = VoiceCommandStore::new(temp_dir.path())?;

        // Single word phrase - should not match
        store.set_phrase("ENTER", "submit")?;
        assert!(!store.could_start_two_word_command("submit"));

        Ok(())
    }

    #[test]
    fn test_could_start_two_word_command_two_word_phrase() -> Result<()> {
        let temp_dir = TempDir::new()?;
        let store = VoiceCommandStore::new(temp_dir.path())?;

        // Two word phrase - first word should match
        store.set_phrase("ENTER", "do enter")?;
        assert!(store.could_start_two_word_command("do"));
        assert!(store.could_start_two_word_command("DO")); // case insensitive
        assert!(!store.could_start_two_word_command("enter")); // second word
        assert!(!store.could_start_two_word_command("other"));

        Ok(())
    }

    #[test]
    fn test_could_start_two_word_command_multiple_phrases() -> Result<()> {
        let temp_dir = TempDir::new()?;
        let store = VoiceCommandStore::new(temp_dir.path())?;

        store.set_phrase("ENTER", "do enter")?;
        store.set_phrase("COPY", "grab it")?;
        store.set_phrase("PASTE", "put")?; // single word

        assert!(store.could_start_two_word_command("do"));
        assert!(store.could_start_two_word_command("grab"));
        assert!(!store.could_start_two_word_command("put")); // single word phrase
        assert!(!store.could_start_two_word_command("other"));

        Ok(())
    }

    #[test]
    fn test_match_phrase_two_words() -> Result<()> {
        let temp_dir = TempDir::new()?;
        let store = VoiceCommandStore::new(temp_dir.path())?;

        store.set_phrase("ENTER", "do enter")?;

        assert_eq!(store.match_phrase("do enter"), Some("ENTER".to_string()));
        assert_eq!(store.match_phrase("DO ENTER"), Some("ENTER".to_string()));
        assert_eq!(store.match_phrase("do"), None); // partial match
        assert_eq!(store.match_phrase("enter"), None); // partial match

        Ok(())
    }
}
