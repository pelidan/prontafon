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

//! X11 text injection using enigo.

use anyhow::Result;
use enigo::{Direction, Enigo, Keyboard, Settings};
use std::sync::Mutex;
use std::thread;
use std::time::Duration;
use tracing::{debug, info, trace};

use super::keys::{Key, Modifier};
use super::InputInjector;

/// Delay between keystrokes in milliseconds.
const KEYSTROKE_DELAY_MS: u64 = 10;

/// X11 input injector using enigo.
pub struct X11Injector {
    enigo: Mutex<Enigo>,
    delay: Duration,
}

impl X11Injector {
    /// Create a new X11 injector.
    pub fn new() -> Result<Self> {
        let enigo = Enigo::new(&Settings::default())?;
        Ok(Self {
            enigo: Mutex::new(enigo),
            delay: Duration::from_millis(KEYSTROKE_DELAY_MS),
        })
    }

    /// Small delay between operations.
    fn pause(&self) {
        if !self.delay.is_zero() {
            thread::sleep(self.delay);
        }
    }
}

impl InputInjector for X11Injector {
    fn backend_name(&self) -> &'static str {
        "X11 (enigo)"
    }

    fn type_text(&self, text: &str) -> Result<()> {
        info!("X11: Typing text: {} chars", text.len());

        let mut enigo = self.enigo.lock().unwrap();

        for c in text.chars() {
            trace!("Typing char: {:?}", c);
            enigo.text(&c.to_string())?;
            self.pause();
        }

        info!("X11: Text typing complete");
        Ok(())
    }

    fn press_key(&self, key: Key) -> Result<()> {
        debug!("Pressing key: {:?}", key);

        let mut enigo = self.enigo.lock().unwrap();
        let enigo_key = key.to_enigo();

        enigo.key(enigo_key, Direction::Click)?;
        self.pause();

        Ok(())
    }

    fn key_combo(&self, modifiers: &[Modifier], key: Key) -> Result<()> {
        debug!("Key combo: {:?} + {:?}", modifiers, key);

        let mut enigo = self.enigo.lock().unwrap();

        // Press modifiers
        for modifier in modifiers {
            let enigo_key = modifier.to_enigo();
            enigo.key(enigo_key, Direction::Press)?;
            self.pause();
        }

        // Press and release the main key
        let enigo_key = key.to_enigo();
        enigo.key(enigo_key, Direction::Click)?;
        self.pause();

        // Release modifiers in reverse order
        for modifier in modifiers.iter().rev() {
            let enigo_key = modifier.to_enigo();
            enigo.key(enigo_key, Direction::Release)?;
            self.pause();
        }

        Ok(())
    }
}
