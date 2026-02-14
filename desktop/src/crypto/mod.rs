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

//! Cryptography module for message encryption and verification.

pub mod ecdh;

use aes_gcm::{
    aead::{Aead, KeyInit, OsRng},
    Aes256Gcm, Nonce,
};
use anyhow::{anyhow, Result};
use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use pbkdf2::pbkdf2_hmac;
use rand::RngCore;
use sha2::{Digest, Sha256};

const PBKDF2_ITERATIONS: u32 = 100_000;
const SALT: &[u8] = b"prontafon_v1";
const NONCE_SIZE: usize = 12;
const KEY_SIZE: usize = 32;

/// Cryptographic context for a paired session.
#[derive(Clone)]
pub struct CryptoContext {
    key: [u8; KEY_SIZE],
}

impl CryptoContext {
    /// Create a new crypto context from a shared secret.
    #[allow(dead_code)]
    pub fn new(key: [u8; KEY_SIZE]) -> Self {
        Self { key }
    }

    /// Derive a crypto context from PIN and device IDs.
    #[allow(dead_code)]
    pub fn from_pin(pin: &str, android_id: &str, linux_id: &str) -> Self {
        let key = derive_key(pin, android_id, linux_id);
        Self { key }
    }

    /// Create from ECDH shared secret and device IDs.
    pub fn from_ecdh(shared_secret: &[u8; 32], android_id: &str, linux_id: &str) -> Self {
        let key = derive_key_from_ecdh(shared_secret, android_id, linux_id);
        Self { key }
    }

    /// Encrypt a plaintext message.
    pub fn encrypt(&self, plaintext: &str) -> Result<String> {
        encrypt(plaintext, &self.key)
    }

    /// Decrypt a ciphertext message.
    pub fn decrypt(&self, ciphertext: &str) -> Result<String> {
        decrypt(ciphertext, &self.key)
    }

    /// Calculate checksum for a message.
    pub fn checksum(&self, version: u8, msg_type: &str, payload: &str, timestamp: u64) -> String {
        checksum(version, msg_type, payload, timestamp, &self.key)
    }

    /// Verify a message checksum.
    pub fn verify_checksum(
        &self,
        version: u8,
        msg_type: &str,
        payload: &str,
        timestamp: u64,
        expected: &str,
    ) -> bool {
        let calculated = self.checksum(version, msg_type, payload, timestamp);
        calculated == expected
    }
}

/// Derive a 256-bit key from PIN and device identifiers.
#[allow(dead_code)]
pub fn derive_key(pin: &str, android_id: &str, linux_id: &str) -> [u8; KEY_SIZE] {
    let password = format!("{}{}{}", pin, android_id, linux_id);
    let mut key = [0u8; KEY_SIZE];

    pbkdf2_hmac::<Sha256>(password.as_bytes(), SALT, PBKDF2_ITERATIONS, &mut key);

    key
}

/// Derive a 256-bit key from ECDH shared secret and device identifiers.
/// The shared secret provides the cryptographic strength, device IDs provide binding.
pub fn derive_key_from_ecdh(
    shared_secret: &[u8; 32],
    android_id: &str,
    linux_id: &str,
) -> [u8; KEY_SIZE] {
    let password = format!("{}{}{}", hex::encode(shared_secret), android_id, linux_id);
    let mut key = [0u8; KEY_SIZE];
    pbkdf2_hmac::<Sha256>(password.as_bytes(), SALT, PBKDF2_ITERATIONS, &mut key);
    key
}

/// Encrypt plaintext using AES-256-GCM.
/// Returns base64(nonce || ciphertext || tag).
pub fn encrypt(plaintext: &str, key: &[u8; KEY_SIZE]) -> Result<String> {
    let cipher =
        Aes256Gcm::new_from_slice(key).map_err(|e| anyhow!("Failed to create cipher: {}", e))?;

    // Generate random nonce
    let mut nonce_bytes = [0u8; NONCE_SIZE];
    OsRng.fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);

    // Encrypt
    let ciphertext = cipher
        .encrypt(nonce, plaintext.as_bytes())
        .map_err(|e| anyhow!("Encryption failed: {}", e))?;

    // Combine nonce and ciphertext
    let mut combined = Vec::with_capacity(NONCE_SIZE + ciphertext.len());
    combined.extend_from_slice(&nonce_bytes);
    combined.extend_from_slice(&ciphertext);

    Ok(BASE64.encode(combined))
}

/// Decrypt ciphertext using AES-256-GCM.
/// Expects base64(nonce || ciphertext || tag).
pub fn decrypt(ciphertext: &str, key: &[u8; KEY_SIZE]) -> Result<String> {
    let combined = BASE64
        .decode(ciphertext)
        .map_err(|e| anyhow!("Base64 decode failed: {}", e))?;

    if combined.len() < NONCE_SIZE {
        return Err(anyhow!("Ciphertext too short"));
    }

    let (nonce_bytes, ciphertext_bytes) = combined.split_at(NONCE_SIZE);
    let nonce = Nonce::from_slice(nonce_bytes);

    let cipher =
        Aes256Gcm::new_from_slice(key).map_err(|e| anyhow!("Failed to create cipher: {}", e))?;

    let plaintext = cipher
        .decrypt(nonce, ciphertext_bytes)
        .map_err(|e| anyhow!("Decryption failed: {}", e))?;

    String::from_utf8(plaintext).map_err(|e| anyhow!("UTF-8 decode failed: {}", e))
}

/// Calculate SHA-256 checksum (first 8 hex characters).
pub fn checksum(
    version: u8,
    msg_type: &str,
    payload: &str,
    timestamp: u64,
    secret: &[u8],
) -> String {
    let mut hasher = Sha256::new();
    hasher.update(version.to_string().as_bytes());
    hasher.update(msg_type.as_bytes());
    hasher.update(payload.as_bytes());
    hasher.update(timestamp.to_string().as_bytes());
    hasher.update(secret);

    let hash = hasher.finalize();
    hex::encode(&hash[..4]) // First 4 bytes = 8 hex chars
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_key_derivation() {
        let key1 = derive_key("123456", "android-abc", "linux-xyz");
        let key2 = derive_key("123456", "android-abc", "linux-xyz");
        let key3 = derive_key("654321", "android-abc", "linux-xyz");

        assert_eq!(key1, key2);
        assert_ne!(key1, key3);
    }

    #[test]
    fn test_encrypt_decrypt() {
        let key = derive_key("123456", "android-abc", "linux-xyz");
        let plaintext = "Hello, World!";

        let encrypted = encrypt(plaintext, &key).unwrap();
        let decrypted = decrypt(&encrypted, &key).unwrap();

        assert_eq!(plaintext, decrypted);
        assert_ne!(plaintext, encrypted);
    }

    #[test]
    fn test_checksum() {
        let key = derive_key("123456", "android-abc", "linux-xyz");
        let cs1 = checksum(1, "TEXT", "hello", 1234567890, &key);
        let cs2 = checksum(1, "TEXT", "hello", 1234567890, &key);
        let cs3 = checksum(1, "TEXT", "world", 1234567890, &key);

        assert_eq!(cs1.len(), 8);
        assert_eq!(cs1, cs2);
        assert_ne!(cs1, cs3);
    }

    #[test]
    fn test_crypto_context() {
        let ctx = CryptoContext::from_pin("123456", "android-abc", "linux-xyz");

        let encrypted = ctx.encrypt("test message").unwrap();
        let decrypted = ctx.decrypt(&encrypted).unwrap();

        assert_eq!("test message", decrypted);

        let cs = ctx.checksum(1, "TEXT", "payload", 12345);
        assert!(ctx.verify_checksum(1, "TEXT", "payload", 12345, &cs));
        assert!(!ctx.verify_checksum(1, "TEXT", "different", 12345, &cs));
    }
}
