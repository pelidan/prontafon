# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.0] - 2026-02-02

### Added
- **Hybrid Restart Strategy**: Implemented soft/hard restart approach for SpeechRecognizer
  - First attempts soft restart (cancel + restart) for faster recovery
  - Falls back to hard restart (destroy + recreate) after consecutive failures
  - Configurable thresholds and timeouts for optimal reliability

### Changed
- Updated Android dependencies to latest stable versions
- Improved code quality with additional lint warnings resolved
- Added notification permission handling for Android 13+

### Fixed
- Resolved build warnings in Android and Desktop components
- Fixed lint warnings and improved code quality
- Removed obsolete SDK version checks

### Technical
- Desktop: Cleaned up unused code and resolved Clippy warnings
- Android: Updated Gradle, Kotlin, and Compose dependencies
- Improved error handling in SpeechRecognitionManager

## [0.4.0] - 2026-02-01

### Changed
- **BREAKING**: Migrated Android app from Flutter to native Kotlin + Jetpack Compose
- Complete rewrite of Android codebase using modern Android architecture (MVVM, Hilt, Coroutines)
- Improved performance and reduced APK size with native implementation
- Better Android platform integration and lifecycle management
- Enhanced BLE reliability with improved state management

### Fixed
- Fixed incomplete message delivery when speaking repeated words
- Fixed pairing state synchronization between Android and desktop
- Improved duplicate message filtering algorithm
- Better connection state machine with distinct PAIRING state

### Technical
- Android: Migrated to 100% Kotlin with Jetpack Compose UI
- Android: Implemented MVVM architecture with Hilt dependency injection
- Android: Added comprehensive unit and integration tests
- Desktop: Version bumped to 0.4.0 for consistency
- Improved code maintainability and type safety

## [0.2.5] - 2026-01-31

### Fixed
- **BLE Pairing**: Fixed GATT service registration bug where service was unregistered immediately after creation
- **BLE Pairing**: Implemented complete PIN pairing flow with GTK dialog on desktop
- **BLE Pairing**: Fixed checksum calculation mismatch between Android (Dart) and Desktop (Rust)
- **BLE Pairing**: Fixed PAIR_ACK signing issue (chicken-and-egg problem with key derivation)
- **BLE Pairing**: Fixed reconnection flow to always send PAIR_REQ
- **BLE Pairing**: Fixed crypto key synchronization when desktop requests fresh pairing
- **Speech Recognition**: Fixed crash loop from aggressive auto-restart on errors
- **Speech Recognition**: Fixed `error_speech_timeout` being treated as real error instead of normal state
- **Speech Recognition**: Fixed `error_busy` recovery being blocked by rate limiter
- **Speech Recognition**: Implemented recognizer state machine to prevent race conditions

### Added
- Exponential backoff for speech recognition errors (2s, 4s, 8s... up to 30s)
- Configurable listening parameters (`pauseFor`, `listenFor` durations)
- Watchdog timer (20s) for long-running stability - enables 1+ hour sessions
- State stuck detection (10s timeout for `starting`/`stopping` states)
- Automatic trailing space after each message to prevent joined words

### Changed
- Reduced text buffer delay from 150ms to 50ms for faster response
- Speech recognition now restarts immediately after successful recognition (no debounce)
- Transient errors (`error_client`, `error_speech_timeout`, `error_no_match`) no longer show error UI
- ACK timeout increased from 5s to 10s for more reliable pairing
- PIN dialog now has 60-second auto-close timeout

### Technical
- Desktop: Store `ApplicationHandle` to keep GATT service registered
- Desktop: Send unsigned PAIR_ACK (Android needs device ID to derive key)
- Android: Use raw bytes for checksum calculation (matching Rust implementation)
- Android: `RecognizerState` enum for state machine (`idle`, `starting`, `listening`, `stopping`)
- Android: Watchdog checks every 5s for stuck states and missed restarts

## [0.2.0] - 2026-01-31

### Changed
- **BREAKING**: Migrated from Bluetooth Classic (RFCOMM) to Bluetooth Low Energy (BLE)
- Improved connection speed and power efficiency with BLE
- Better Android 12+ compatibility with modern BLE APIs
- Android app now uses BLE GATT client instead of RFCOMM
- Linux desktop now runs BLE GATT server instead of RFCOMM server

### Removed
- Bluetooth Classic (RFCOMM) support
- Legacy `bluetooth_service.dart` (replaced by `ble_service.dart`)
- Legacy `bluetooth_device.dart` model (replaced by `ble_device.dart`)
- RFCOMM server and connection modules from desktop app

### Added
- BLE GATT server implementation on Linux desktop
- BLE GATT client implementation on Android
- Packet chunking and reassembly for BLE MTU limits
- 30 comprehensive unit tests for BLE functionality
- Integration tests for cross-platform encryption and messaging

### Technical Details
- BLE provides lower power consumption compared to Classic Bluetooth
- Better compatibility with modern Android versions (12+)
- More reliable connection management with BLE GATT
- Packet fragmentation handles MTU constraints transparently

### Migration Notes
For users upgrading from 0.1.x:
- Both Android app and Linux desktop must be updated simultaneously
- Old RFCOMM pairings will not work with BLE version
- Re-pair devices after updating to 0.2.0
- BLE requires Bluetooth 4.0+ hardware (most devices from 2013+)

## [0.1.4] - 2026-01-31

### Added
- Initial release with Bluetooth Classic (RFCOMM) support
- Speech-to-text dictation from Android to Linux desktop
- AES-256-GCM encryption for all communications
- Voice commands (new line, select all, copy, paste, cut)
- System tray integration on Linux
- History tracking with SQLite
- Support for X11 and Wayland (via ydotool)
- Flutter-based Android app
- Rust/GTK4-based Linux desktop app

### Security
- PIN-based pairing with 6-digit codes
- End-to-end encryption using AES-256-GCM
- HMAC-SHA256 message signing
- Secure key derivation with PBKDF2

[0.6.0]: https://github.com/pelidan/prontafon/compare/v0.4.0...v0.6.0
[0.4.0]: https://github.com/pelidan/prontafon/compare/v0.2.5...v0.4.0
[0.2.5]: https://github.com/pelidan/prontafon/compare/v0.2.0...v0.2.5
[0.2.0]: https://github.com/pelidan/prontafon/compare/v0.1.4...v0.2.0
[0.1.4]: https://github.com/pelidan/prontafon/releases/tag/v0.1.4
