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

//! Test utility for input injection.
//!
//! Usage: cargo run --bin test_input -- [text|key|combo]

use anyhow::Result;
use prontafon_desktop::input::{create_injector, Key, Modifier};
use std::env;
use std::thread;
use std::time::Duration;

fn main() -> Result<()> {
    // Initialize logging
    tracing_subscriber::fmt::init();

    let args: Vec<String> = env::args().collect();
    let mode = args.get(1).map(|s| s.as_str()).unwrap_or("text");

    println!("Creating input injector...");
    let injector = create_injector()?;
    println!("Using backend: {}", injector.backend_name());

    println!("You have 3 seconds to focus a text editor...");
    thread::sleep(Duration::from_secs(3));

    match mode {
        "text" => {
            println!("Typing test text...");
            injector.type_text("Hello from Prontafon!")?;
            println!("Done!");
        }
        "key" => {
            println!("Pressing Enter...");
            injector.press_key(Key::Enter)?;
            println!("Done!");
        }
        "combo" => {
            println!("Pressing Ctrl+A (select all)...");
            injector.key_combo(&[Modifier::Ctrl], Key::A)?;
            println!("Done!");
        }
        "full" => {
            println!("Running full test sequence...");

            // Type some text
            injector.type_text("Line 1")?;
            thread::sleep(Duration::from_millis(100));

            // Press Enter
            injector.press_key(Key::Enter)?;
            thread::sleep(Duration::from_millis(100));

            // Type more text
            injector.type_text("Line 2")?;
            thread::sleep(Duration::from_millis(100));

            // Select all
            injector.key_combo(&[Modifier::Ctrl], Key::A)?;
            thread::sleep(Duration::from_millis(500));

            println!("Done! Text should be selected.");
        }
        _ => {
            println!("Unknown mode: {}", mode);
            println!("Usage: test_input [text|key|combo|full]");
        }
    }

    Ok(())
}
