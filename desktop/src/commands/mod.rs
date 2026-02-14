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

//! Voice command execution module.
//!
//! Maps received commands to keyboard actions.

use anyhow::Result;
use tracing::debug;

use crate::input::{InputInjector, Key, Modifier};

mod matcher;
pub use matcher::{CombinedMatcher, MatchResult, TextSegment};

mod word_buffer;
pub use word_buffer::{ProcessedItem, WordBuffer};

/// Voice command types.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VoiceCommand {
    /// Press Enter key.
    Enter,
    /// Select all (Ctrl+A).
    SelectAll,
    /// Copy (Ctrl+C).
    Copy,
    /// Paste (Ctrl+V).
    Paste,
    /// Cut (Ctrl+X).
    Cut,
    /// Cancel/discard (no action).
    Cancel,
}

impl VoiceCommand {
    /// Parse from string code.
    pub fn parse(s: &str) -> Option<Self> {
        match s.trim().to_uppercase().as_str() {
            "ENTER" => Some(Self::Enter),
            "SELECT_ALL" => Some(Self::SelectAll),
            "COPY" => Some(Self::Copy),
            "PASTE" => Some(Self::Paste),
            "CUT" => Some(Self::Cut),
            "CANCEL" => Some(Self::Cancel),
            _ => None,
        }
    }

    /// Get string code.
    #[allow(dead_code)]
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Enter => "ENTER",
            Self::SelectAll => "SELECT_ALL",
            Self::Copy => "COPY",
            Self::Paste => "PASTE",
            Self::Cut => "CUT",
            Self::Cancel => "CANCEL",
        }
    }
}

/// Execute a voice command using the given injector.
pub fn execute(command: &VoiceCommand, injector: &dyn InputInjector) -> Result<()> {
    debug!("Executing command: {:?}", command);

    match command {
        VoiceCommand::Enter => injector.press_key(Key::Enter),
        VoiceCommand::SelectAll => injector.key_combo(&[Modifier::Ctrl], Key::A),
        VoiceCommand::Copy => injector.key_combo(&[Modifier::Ctrl], Key::C),
        VoiceCommand::Paste => injector.key_combo(&[Modifier::Ctrl], Key::V),
        VoiceCommand::Cut => injector.key_combo(&[Modifier::Ctrl], Key::X),
        VoiceCommand::Cancel => {
            debug!("Cancel command - no action taken");
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_voice_command_parse() {
        assert_eq!(VoiceCommand::parse("ENTER"), Some(VoiceCommand::Enter));
        assert_eq!(VoiceCommand::parse("enter"), Some(VoiceCommand::Enter));
        assert_eq!(
            VoiceCommand::parse("SELECT_ALL"),
            Some(VoiceCommand::SelectAll)
        );
        assert_eq!(VoiceCommand::parse("COPY"), Some(VoiceCommand::Copy));
        assert_eq!(VoiceCommand::parse("PASTE"), Some(VoiceCommand::Paste));
        assert_eq!(VoiceCommand::parse("CUT"), Some(VoiceCommand::Cut));
        assert_eq!(VoiceCommand::parse("CANCEL"), Some(VoiceCommand::Cancel));
        assert_eq!(VoiceCommand::parse("INVALID"), None);
    }
}
