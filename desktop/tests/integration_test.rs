//! Integration tests for the encryption flow.

use prontafon_desktop::crypto::ecdh::EcdhKeypair;
use prontafon_desktop::crypto::CryptoContext;

#[test]
fn test_ecdh_key_exchange() {
    // Simulate ECDH key exchange between Android and Linux
    let android_keypair = EcdhKeypair::generate();
    let linux_keypair = EcdhKeypair::generate();

    // Get public keys before consuming keypairs
    let android_public = android_keypair.public_key_bytes();
    let linux_public = linux_keypair.public_key_bytes();

    // Exchange public keys and compute shared secrets (consumes keypairs)
    let android_shared = android_keypair.compute_shared_secret(&linux_public);
    let linux_shared = linux_keypair.compute_shared_secret(&android_public);

    // Both sides should derive the same shared secret
    assert_eq!(android_shared, linux_shared);
}

#[test]
fn test_ecdh_based_encryption() {
    // Simulate full ECDH-based encryption flow
    let android_id = "android-test123";
    let linux_id = "linux-test456";

    // Key exchange
    let android_keypair = EcdhKeypair::generate();
    let linux_keypair = EcdhKeypair::generate();

    let android_public = android_keypair.public_key_bytes();
    let linux_public = linux_keypair.public_key_bytes();

    let android_shared = android_keypair.compute_shared_secret(&linux_public);
    let linux_shared = linux_keypair.compute_shared_secret(&android_public);

    // Create crypto contexts from ECDH shared secret
    let android_ctx = CryptoContext::from_ecdh(&android_shared, android_id, linux_id);
    let linux_ctx = CryptoContext::from_ecdh(&linux_shared, android_id, linux_id);

    // Encrypt on Android side
    let plaintext = "Hello from Android!";
    let encrypted = android_ctx.encrypt(plaintext).unwrap();

    // Decrypt on Linux side
    let decrypted = linux_ctx.decrypt(&encrypted).unwrap();

    assert_eq!(plaintext, decrypted);
}

#[test]
fn test_checksum_verification() {
    // Test message integrity verification
    let android_id = "android-test123";
    let linux_id = "linux-test456";

    let android_keypair = EcdhKeypair::generate();
    let linux_keypair = EcdhKeypair::generate();

    let linux_public = linux_keypair.public_key_bytes();
    let shared = android_keypair.compute_shared_secret(&linux_public);

    let ctx = CryptoContext::from_ecdh(&shared, android_id, linux_id);

    // Calculate checksum
    let checksum = ctx.checksum(3, "WORD", "hello", 1234567890);

    // Verify passes with correct data
    assert!(ctx.verify_checksum(3, "WORD", "hello", 1234567890, &checksum));

    // Verify fails with tampered data
    assert!(!ctx.verify_checksum(3, "WORD", "tampered", 1234567890, &checksum));
    assert!(!ctx.verify_checksum(3, "WORD", "hello", 1234567891, &checksum));
}

#[test]
fn test_device_id_binding() {
    // Test that different device IDs produce different keys
    // Generate keypairs for key exchange
    let keypair1 = EcdhKeypair::generate();
    let keypair2 = EcdhKeypair::generate();
    let public2 = keypair2.public_key_bytes();

    // Compute shared secret
    let shared = keypair1.compute_shared_secret(&public2);

    // Use the same shared secret but different device IDs
    // Same secret + different IDs = different derived keys
    let ctx1 = CryptoContext::from_ecdh(&shared, "android-1", "linux-1");
    let ctx2 = CryptoContext::from_ecdh(&shared, "android-2", "linux-2");

    // Encryption with one context cannot be decrypted by another (different device binding)
    let encrypted = ctx1.encrypt("test").unwrap();
    assert!(ctx2.decrypt(&encrypted).is_err());
}
