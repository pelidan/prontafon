# Prontafon

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A voice-to-keyboard bridge that lets you dictate text directly into any application on your Linux desktop using your Android phone.

## Features

- **Seamless Dictation**: Speak on your phone, text appears at cursor on your PC
- **Voice Commands**: Control your desktop with commands like "enter", "select all", "copy"
- **Secure Connection**: AES-256 encrypted Bluetooth Low Energy (BLE) communication
- **Works Everywhere**: Types into any application - IDEs, browsers, terminals
- **Low Latency**: Direct BLE connection, no internet required

## Components

| Component | Technology | Description |
|-----------|------------|-------------|
| Android App | Kotlin + Jetpack Compose | Speech recognition, BLE GATT client |
| Linux Desktop | Rust + GTK4 | BLE GATT server, keyboard simulation, system tray |

## Quick Start

### Linux Desktop

```bash
# Install
cd desktop
./scripts/install.sh

# Start
prontafon-desktop
# Or: systemctl --user start prontafon
```

### Android

1. Install the APK from releases
2. Grant microphone and Bluetooth permissions
3. Open Prontafon and scan for devices
4. Select your computer and connect

### First-Time Pairing

1. Start the desktop app (it will appear in system tray)
2. Open the Android app and tap "Scan for Devices"
3. Select your computer from the list and tap "Connect"
4. Accept the connection on your desktop when prompted
5. Start speaking!

## Voice Commands

Default voice commands:

| Say | Action |
|-----|--------|
| "enter" | Press Enter |
| "select all" | Ctrl+A |
| "copy" | Ctrl+C |
| "paste" | Ctrl+V |
| "cut" | Ctrl+X |
| "cancel" | Discard current text |

### Custom Voice Commands

You can customize voice command phrases by editing `~/.config/prontafon/voice_commands.json`:

```json
{
  "version": 1,
  "mappings": [
    {"phrase": "enter", "command": "ENTER", "created_at": "2024-01-01T00:00:00Z"},
    {"phrase": "select all", "command": "SELECT_ALL", "created_at": "2024-01-01T00:00:00Z"},
    {"phrase": "copy", "command": "COPY", "created_at": "2024-01-01T00:00:00Z"},
    {"phrase": "paste", "command": "PASTE", "created_at": "2024-01-01T00:00:00Z"},
    {"phrase": "cut", "command": "CUT", "created_at": "2024-01-01T00:00:00Z"},
    {"phrase": "cancel", "command": "CANCEL", "created_at": "2024-01-01T00:00:00Z"}
  ]
}
```

The desktop app will reload custom phrases automatically when the file changes.

## Building from Source

### Prerequisites

- **Android**: Android Studio, JDK 17+, Android SDK (API 35 for target, API 26+ minimum)
- **Linux**: Rust 1.70+, GTK4 dev libraries, BlueZ 5.50+

### Build

```bash
# Android
cd android/android
./gradlew assembleDebug
# Or for release: ./gradlew assembleRelease

# Linux
cd desktop
cargo build --release
```

## System Requirements

### Android
- Android 8.0 (API 26) or higher
- Bluetooth Low Energy (BLE) support
- Microphone

### Linux
- X11 or Wayland (with ydotool)
- Bluetooth adapter with BLE support
- BlueZ 5.x with BLE GATT support

## Configuration

Desktop config: `~/.config/prontafon/config.toml`

```toml
[bluetooth]
# Note: device_name is automatically set to the computer's hostname
auto_accept = true  # Auto-accept reconnections from previously paired devices

[input]
typing_delay_ms = 10  # Delay between keystrokes
prefer_backend = "auto"  # Options: "auto", "x11", "wayland"
```

## Troubleshooting

### Bluetooth not connecting
```bash
# Check Bluetooth status
sudo systemctl status bluetooth

# Restart Bluetooth
sudo systemctl restart bluetooth

# Ensure your adapter supports BLE
hciconfig -a | grep -i "le"

# Check if device is paired
bluetoothctl paired-devices
```

### Text not typing (Wayland)
```bash
# Install ydotool
sudo apt install ydotool

# Start ydotool daemon
systemctl --user enable ydotoold
systemctl --user start ydotoold
```

### Permission issues on Android
- Go to Settings > Apps > Prontafon > Permissions
- Enable Microphone and Nearby devices (Bluetooth)

## Releases

### Installing Pre-built Packages

Download the latest release from the [Releases page](https://github.com/pelidan/prontafon/releases).

**Linux:**
- `.deb` - For Ubuntu/Debian: `sudo dpkg -i prontafon-*.deb`
- `.rpm` - For Fedora/RHEL: `sudo rpm -i prontafon-*.rpm`
- `.AppImage` - Universal: `chmod +x Prontafon-*.AppImage && ./Prontafon-*.AppImage`

**Android:**
- `.apk` - Enable "Install from Unknown Sources", then install

### Creating a Release

For maintainers: Releases are automated via GitHub Actions.

1. Ensure all tests pass and changes are committed
2. Go to Actions → Release Build → Run workflow
3. Enter the version number (e.g., `1.2.3`)
4. The workflow will:
   - Update version numbers
   - Create a git tag
   - Build all packages (deb, rpm, AppImage, APK)
   - Create a GitHub Release with all artifacts

See [.github/workflows/README.md](.github/workflows/README.md) for detailed setup instructions.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

### Patent Grant

The Apache 2.0 license includes an explicit patent grant, which means:
- You receive patent rights for any patentable aspects of the software
- Contributors grant you patent licenses for their contributions
- If someone sues claiming patent infringement, their license terminates

For the full license terms, see [http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0)

## Contributing

Contributions welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Submit a pull request

For bug reports and feature requests, please open an issue on GitHub.