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
#[allow(dead_code)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Modifier {
    Ctrl,
    Alt,
    Shift,
    Super,
}

impl Modifier {
    /// Get the enigo key for this modifier.
    #[cfg(feature = "x11")]
    pub fn to_enigo(self) -> enigo::Key {
        match self {
            Modifier::Ctrl => enigo::Key::Control,
            Modifier::Alt => enigo::Key::Alt,
            Modifier::Shift => enigo::Key::Shift,
            Modifier::Super => enigo::Key::Meta,
        }
    }

    /// Get the ydotool key name for this modifier.
    pub fn to_ydotool(self) -> &'static str {
        match self {
            Modifier::Ctrl => "LEFTCTRL",
            Modifier::Alt => "LEFTALT",
            Modifier::Shift => "LEFTSHIFT",
            Modifier::Super => "LEFTMETA",
        }
    }
}

/// Special keys.
#[allow(dead_code)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Key {
    // Letters
    A,
    B,
    C,
    D,
    E,
    F,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
    O,
    P,
    Q,
    R,
    S,
    T,
    U,
    V,
    W,
    X,
    Y,
    Z,

    // Special keys
    Enter,
    Backspace,
    Tab,
    Escape,
    Space,
    Delete,
    Home,
    End,
    PageUp,
    PageDown,
    Left,
    Right,
    Up,
    Down,

    // Function keys
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
}

impl Key {
    /// Get the enigo key.
    #[cfg(feature = "x11")]
    pub fn to_enigo(self) -> enigo::Key {
        use enigo::Key as EKey;
        match self {
            Key::A => EKey::Unicode('a'),
            Key::B => EKey::Unicode('b'),
            Key::C => EKey::Unicode('c'),
            Key::D => EKey::Unicode('d'),
            Key::E => EKey::Unicode('e'),
            Key::F => EKey::Unicode('f'),
            Key::G => EKey::Unicode('g'),
            Key::H => EKey::Unicode('h'),
            Key::I => EKey::Unicode('i'),
            Key::J => EKey::Unicode('j'),
            Key::K => EKey::Unicode('k'),
            Key::L => EKey::Unicode('l'),
            Key::M => EKey::Unicode('m'),
            Key::N => EKey::Unicode('n'),
            Key::O => EKey::Unicode('o'),
            Key::P => EKey::Unicode('p'),
            Key::Q => EKey::Unicode('q'),
            Key::R => EKey::Unicode('r'),
            Key::S => EKey::Unicode('s'),
            Key::T => EKey::Unicode('t'),
            Key::U => EKey::Unicode('u'),
            Key::V => EKey::Unicode('v'),
            Key::W => EKey::Unicode('w'),
            Key::X => EKey::Unicode('x'),
            Key::Y => EKey::Unicode('y'),
            Key::Z => EKey::Unicode('z'),
            Key::Enter => EKey::Return,
            Key::Backspace => EKey::Backspace,
            Key::Tab => EKey::Tab,
            Key::Escape => EKey::Escape,
            Key::Space => EKey::Space,
            Key::Delete => EKey::Delete,
            Key::Home => EKey::Home,
            Key::End => EKey::End,
            Key::PageUp => EKey::PageUp,
            Key::PageDown => EKey::PageDown,
            Key::Left => EKey::LeftArrow,
            Key::Right => EKey::RightArrow,
            Key::Up => EKey::UpArrow,
            Key::Down => EKey::DownArrow,
            Key::F1 => EKey::F1,
            Key::F2 => EKey::F2,
            Key::F3 => EKey::F3,
            Key::F4 => EKey::F4,
            Key::F5 => EKey::F5,
            Key::F6 => EKey::F6,
            Key::F7 => EKey::F7,
            Key::F8 => EKey::F8,
            Key::F9 => EKey::F9,
            Key::F10 => EKey::F10,
            Key::F11 => EKey::F11,
            Key::F12 => EKey::F12,
        }
    }

    /// Get the ydotool key name.
    pub fn to_ydotool(self) -> &'static str {
        match self {
            Key::A => "A",
            Key::B => "B",
            Key::C => "C",
            Key::D => "D",
            Key::E => "E",
            Key::F => "F",
            Key::G => "G",
            Key::H => "H",
            Key::I => "I",
            Key::J => "J",
            Key::K => "K",
            Key::L => "L",
            Key::M => "M",
            Key::N => "N",
            Key::O => "O",
            Key::P => "P",
            Key::Q => "Q",
            Key::R => "R",
            Key::S => "S",
            Key::T => "T",
            Key::U => "U",
            Key::V => "V",
            Key::W => "W",
            Key::X => "X",
            Key::Y => "Y",
            Key::Z => "Z",
            Key::Enter => "ENTER",
            Key::Backspace => "BACKSPACE",
            Key::Tab => "TAB",
            Key::Escape => "ESC",
            Key::Space => "SPACE",
            Key::Delete => "DELETE",
            Key::Home => "HOME",
            Key::End => "END",
            Key::PageUp => "PAGEUP",
            Key::PageDown => "PAGEDOWN",
            Key::Left => "LEFT",
            Key::Right => "RIGHT",
            Key::Up => "UP",
            Key::Down => "DOWN",
            Key::F1 => "F1",
            Key::F2 => "F2",
            Key::F3 => "F3",
            Key::F4 => "F4",
            Key::F5 => "F5",
            Key::F6 => "F6",
            Key::F7 => "F7",
            Key::F8 => "F8",
            Key::F9 => "F9",
            Key::F10 => "F10",
            Key::F11 => "F11",
            Key::F12 => "F12",
        }
    }
}
