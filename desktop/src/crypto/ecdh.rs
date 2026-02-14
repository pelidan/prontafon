// Copyright 2026 Daniel Pelikan
// SPDX-License-Identifier: Apache-2.0

//! ECDH key exchange using X25519.

use anyhow::{anyhow, Result};
use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use rand::rngs::OsRng;
use x25519_dalek::{EphemeralSecret, PublicKey};

/// X25519 public key size in bytes.
pub const PUBLIC_KEY_SIZE: usize = 32;

/// X25519 shared secret size in bytes.
pub const SHARED_SECRET_SIZE: usize = 32;

/// ECDH keypair for key exchange.
pub struct EcdhKeypair {
    secret: EphemeralSecret,
    public_key: PublicKey,
}

impl EcdhKeypair {
    /// Generate a new random keypair.
    pub fn generate() -> Self {
        let secret = EphemeralSecret::random_from_rng(OsRng);
        let public_key = PublicKey::from(&secret);
        Self { secret, public_key }
    }

    /// Get the public key as bytes.
    #[allow(dead_code)]
    pub fn public_key_bytes(&self) -> [u8; PUBLIC_KEY_SIZE] {
        *self.public_key.as_bytes()
    }

    /// Get the public key as base64.
    pub fn public_key_base64(&self) -> String {
        BASE64.encode(self.public_key.as_bytes())
    }

    /// Compute shared secret with a peer's public key.
    /// Consumes self because EphemeralSecret can only be used once.
    pub fn compute_shared_secret(
        self,
        peer_public_key: &[u8; PUBLIC_KEY_SIZE],
    ) -> [u8; SHARED_SECRET_SIZE] {
        let peer_key = PublicKey::from(*peer_public_key);
        let shared_secret = self.secret.diffie_hellman(&peer_key);
        *shared_secret.as_bytes()
    }

    /// Compute shared secret from base64-encoded peer public key.
    pub fn compute_shared_secret_base64(
        self,
        peer_public_key_base64: &str,
    ) -> Result<[u8; SHARED_SECRET_SIZE]> {
        let peer_bytes = BASE64
            .decode(peer_public_key_base64)
            .map_err(|e| anyhow!("Invalid base64 public key: {}", e))?;

        if peer_bytes.len() != PUBLIC_KEY_SIZE {
            return Err(anyhow!(
                "Invalid public key size: expected {}, got {}",
                PUBLIC_KEY_SIZE,
                peer_bytes.len()
            ));
        }

        let mut peer_key = [0u8; PUBLIC_KEY_SIZE];
        peer_key.copy_from_slice(&peer_bytes);

        Ok(self.compute_shared_secret(&peer_key))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_key_exchange() {
        // Simulate Android and Desktop
        let android_keypair = EcdhKeypair::generate();
        let desktop_keypair = EcdhKeypair::generate();

        let android_public = android_keypair.public_key_bytes();
        let desktop_public = desktop_keypair.public_key_bytes();

        // Both compute the same shared secret
        let android_shared = android_keypair.compute_shared_secret(&desktop_public);
        let desktop_shared = desktop_keypair.compute_shared_secret(&android_public);

        assert_eq!(android_shared, desktop_shared);
    }

    #[test]
    fn test_base64_roundtrip() {
        let keypair = EcdhKeypair::generate();
        let base64_key = keypair.public_key_base64();

        // Should be 44 characters (32 bytes * 4/3, padded)
        assert_eq!(base64_key.len(), 44);

        // Decode should give 32 bytes
        let decoded = BASE64.decode(&base64_key).unwrap();
        assert_eq!(decoded.len(), PUBLIC_KEY_SIZE);
    }
}
