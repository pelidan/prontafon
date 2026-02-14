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

//! Key and modifier definitions.

/// Keyboard modifiers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Modifier {
    Ctrl,
}

impl Modifier {
    /// Get the enigo key for this modifier.
    #[cfg(feature = "x11")]
    pub fn to_enigo(self) -> enigo::Key {
        match self {
            Modifier::Ctrl => enigo::Key::Control,
        }
    }

    /// Get the ydotool key name for this modifier.
    pub fn to_ydotool(self) -> &'static str {
        match self {
            Modifier::Ctrl => "LEFTCTRL",
        }
    }
}

/// Special keys.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Key {
    A,
    C,
    V,
    X,
    Enter,
}

impl Key {
    /// Get the enigo key.
    #[cfg(feature = "x11")]
    pub fn to_enigo(self) -> enigo::Key {
        use enigo::Key as EKey;
        match self {
            Key::A => EKey::Unicode('a'),
            Key::C => EKey::Unicode('c'),
            Key::V => EKey::Unicode('v'),
            Key::X => EKey::Unicode('x'),
            Key::Enter => EKey::Return,
        }
    }

    /// Get the ydotool key name.
    pub fn to_ydotool(self) -> &'static str {
        match self {
            Key::A => "A",
            Key::C => "C",
            Key::V => "V",
            Key::X => "X",
            Key::Enter => "ENTER",
        }
    }
}
