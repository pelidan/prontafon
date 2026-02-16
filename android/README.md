# Prontafon Android App

Native Android application for Prontafon voice-to-keyboard bridge, built with Kotlin and Jetpack Compose.

## Overview

This is the Android companion app that captures speech using Android's speech recognition APIs and transmits the transcribed text to the Linux desktop app via Bluetooth Low Energy (BLE).

## Technology Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Architecture**: MVVM with Unidirectional Data Flow
- **Dependency Injection**: Hilt
- **Async**: Coroutines + Flow
- **Minimum SDK**: 26 (Android 8.0)
- **Target SDK**: 35 (Android 15)

## Key Features

- Native speech recognition with automatic restart
- BLE GATT client for low-power communication
- AES-256-GCM encryption with ECDH key exchange
- Voice command processing
- Secure key storage with EncryptedSharedPreferences
- Permission management with Compose integration
- Material 3 Design

## Project Structure

```
android/
├── app/
│   ├── src/main/java/com/prontafon/
│   │   ├── MainActivity.kt
│   │   ├── ProntafonApplication.kt
│   │   ├── data/              # Repositories
│   │   ├── domain/            # Models, use cases
│   │   ├── di/                # Hilt modules
│   │   ├── service/           # BLE, Speech services
│   │   ├── presentation/      # ViewModels, UI
│   │   └── util/              # Helpers, extensions
│   ├── src/test/              # Unit tests
│   └── src/androidTest/       # Integration tests
└── build.gradle.kts
```

## Building

### Debug Build

```bash
cd android/android
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release Build

1. Create keystore (first time only):
   ```bash
   keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias prontafon
   ```

2. Create `key.properties` in project root:
   ```properties
   storeFile=../release-key.jks
   storePassword=YOUR_STORE_PASSWORD
   keyAlias=prontafon
   keyPassword=YOUR_KEY_PASSWORD
   ```

3. Build release APK:
   ```bash
   ./gradlew assembleRelease
   ```

Output: `app/build/outputs/apk/release/app-release.apk`

## Installation

### Via ADB

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Manual Installation

1. Copy APK to Android device
2. Enable "Install from Unknown Sources" in Settings
3. Open APK file and tap Install

## Permissions Required

- **Microphone** (RECORD_AUDIO) - For speech recognition
- **Bluetooth** (BLUETOOTH_CONNECT, BLUETOOTH_SCAN) - For BLE connection
- **Nearby Devices** (Android 12+) - For BLE device discovery

All permissions are requested at runtime with proper rationale dialogs.

## Configuration

The app uses DataStore for preferences. Configuration is managed through the Settings screen in the app.

Key settings:
- Auto-reconnect
- Listening parameters (pause duration, listen timeout)
- Debug logging

## Testing

### Run Unit Tests

```bash
./gradlew test
```

### Run Instrumented Tests

```bash
./gradlew connectedAndroidTest
```

### Test Coverage

```bash
./gradlew testDebugUnitTestCoverage
```

## Development

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17 or higher
- Android SDK with API 35
- Gradle 8.x (managed by wrapper)

### Setup

1. Open `android/android` directory in Android Studio
2. Sync Gradle dependencies
3. Build project
4. Run on emulator or physical device

### Code Style

The project follows Kotlin coding conventions and uses:
- 4-space indentation
- 100-character line limit
- Material Design guidelines for UI

## Troubleshooting

### Build Fails

```bash
# Clean and rebuild
./gradlew clean assembleDebug
```

### App Crashes on Launch

- Check logcat: `adb logcat | grep Prontafon`
- Verify all permissions are granted
- Ensure Bluetooth is enabled on device

### Can't Connect to Desktop

- Verify desktop app is running
- Check Bluetooth is enabled on both devices
- Try unpairing and re-pairing devices
- Check logs for connection errors

## Architecture

The app follows Clean Architecture principles:

```
Presentation Layer (ViewModels, UI)
        ↓
Domain Layer (Use Cases, Models)
        ↓
Data Layer (Repositories, Services)
```

### Key Components

- **BleService**: Manages BLE connection and communication
- **CryptoManager**: AES-256-GCM encryption/decryption
- **EcdhManager**: ECDH key exchange for pairing
- **SecureStorageManager**: Encrypted storage for keys
- **PermissionManager**: Runtime permission handling

## Contributing

When contributing to the Android app:

1. Follow MVVM architecture patterns
2. Write unit tests for business logic
3. Use Hilt for dependency injection
4. Keep UI logic in ViewModels, not Composables
5. Use sealed classes for state management
6. Document public APIs with KDoc

## License

Licensed under the Apache License 2.0. See [LICENSE](../LICENSE) file for details.

## Resources

- [Android Developer Documentation](https://developer.android.com/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-guide.html)
- [Hilt Dependency Injection](https://developer.android.com/training/dependency-injection/hilt-android)
- [Android BLE Guide](https://developer.android.com/guide/topics/connectivity/bluetooth/ble-overview)
