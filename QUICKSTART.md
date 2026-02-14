# Prontafon Quick Start Guide

This guide will walk you through setting up and testing Prontafon on your Linux desktop and Android device.

## Prerequisites Check

Before starting, verify you have everything installed:

```bash
# Check Rust
rustc --version

# Check Android SDK
echo $ANDROID_HOME

# Check system libraries
dpkg -l | grep -E "libgtk-4-dev|libadwaita-1-dev|libdbus-1-dev|libbluetooth-dev|libxdo-dev"
```

## Step 1: Build the Linux Desktop App

### Install Missing Dependencies

```bash
# Install dependencies using the provided script
sudo ./install-deps.sh

# Optional: Install ydotool for Wayland support
sudo apt install ydotool
```

### Build the Desktop Application

```bash
cd desktop
cargo build --release
```

This will take several minutes the first time as Cargo downloads and compiles dependencies.

### Install the Desktop Application

Choose one of these options:

**Option A: User Installation (Recommended for testing)**
```bash
./scripts/install.sh --user
```
This installs to `~/.local/bin/prontafon-desktop`

**Option B: System Installation**
```bash
sudo ./scripts/install.sh --system
```
This installs to `/usr/local/bin/prontafon-desktop`

**Option C: Run Without Installing**
```bash
cargo run --release
```

## Step 2: Start the Desktop App

### Start the Application

```bash
# If installed:
prontafon-desktop

# If running from source:
cd desktop
cargo run --release
```

### What to Expect

- A system tray icon should appear (look for it in your system tray)
- The app will start a BLE GATT server and advertise its presence
- Check the terminal output for any errors

### Enable Bluetooth

Make sure Bluetooth is enabled and your adapter supports BLE:

```bash
# Check Bluetooth status
sudo systemctl status bluetooth

# If not running, start it
sudo systemctl start bluetooth

# Check if your adapter supports BLE
hciconfig -a | grep -i "le"

# Make your computer discoverable
bluetoothctl
# In bluetoothctl:
power on
discoverable on
pairable on
agent on
default-agent
```

## Step 3: Build the Android App

### Option A: Debug Build (Fast, for Testing)

```bash
cd android/android
./gradlew assembleDebug
```

The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

### Option B: Release Build (Optimized)

```bash
cd android/android

# Generate a keystore (one-time setup)
keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias prontafon

# Create key.properties file with your keystore details
# Then build release APK
./gradlew assembleRelease
```

The APK will be at: `app/build/outputs/apk/release/app-release.apk`

## Step 4: Install the Android App

### Transfer APK to Your Phone

**Option A: USB Transfer**
```bash
# Connect your phone via USB and enable File Transfer mode
# Copy the APK:
cp android/android/app/build/outputs/apk/debug/app-debug.apk ~/Downloads/
# Then use your file manager to transfer to phone
```

**Option B: ADB Install (Fastest)**
```bash
# Enable USB Debugging on your Android phone:
# Settings > About Phone > Tap "Build Number" 7 times
# Settings > Developer Options > Enable "USB Debugging"

# Connect via USB and install:
cd android/android
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Option C: Web Transfer**
Upload to Google Drive, Dropbox, or email to yourself, then download on your phone.

### Install on Phone

1. On your Android phone, go to **Settings > Security**
2. Enable **Install from Unknown Sources** or **Install Unknown Apps** (varies by Android version)
3. Open the APK file in your file manager
4. Tap **Install**

## Step 5: Pair and Connect

### Grant Permissions on Android

1. Open the **Prontafon** app
2. Grant **Microphone** permission when prompted
3. Grant **Bluetooth** and **Nearby Devices** permissions when prompted

### Connect to Your Desktop

1. In the Prontafon app, tap the **Scan for Devices** button
2. You should see your computer in the device list (it will show your hostname)
3. Tap your computer's name to initiate connection
4. The desktop app will show a confirmation dialog - click **Accept**
5. Pairing is automatic via secure ECDH key exchange (no system PIN dialog needed)

## Step 6: Start Dictating

1. After successful connection, the app shows the home screen
2. Open any application on your Linux desktop (text editor, terminal, browser, etc.)
3. Click to position the cursor where you want text to appear
4. **Tap the microphone button** in the Prontafon app
5. **Start speaking!**

The text should appear at your cursor position in real-time.

## Step 7: Voice Commands (Implemented)

Try these voice commands while dictating:

| Say This | What Happens |
|----------|-------------|
| "enter" | Inserts a new line (Enter key) |
| "select all" | Selects all text (Ctrl+A) |
| "copy" | Copies selected text (Ctrl+C) |
| "paste" | Pastes clipboard (Ctrl+V) |
| "cut" | Cuts selected text (Ctrl+X) |
| "cancel" | Discards the current text buffer |

Voice commands can be customized in `~/.config/prontafon/voice_commands.json`.

## Troubleshooting

### Desktop App Won't Start

```bash
# Check for missing libraries
ldd prontafon-desktop | grep "not found"

# Check Bluetooth service
sudo systemctl status bluetooth

# Check logs
journalctl --user -u prontafon -f
```

### Android App Won't Build

```bash
# Clean and rebuild
cd android/android
./gradlew clean assembleDebug
```

### Connection Issues

1. **Make sure Bluetooth is enabled** on both devices
2. **Check pairing** in the Prontafon app (Scan for Devices)
3. **Restart both apps**
4. **Check desktop app logs** in the terminal
5. **Try unpairing and re-pairing** the devices from the app

### Bluetooth Pairing Fails

If you have old pairings, they might interfere:
```bash
# On Linux, remove old pairing:
bluetoothctl
devices
remove <PHONE_MAC_ADDRESS>

# Then scan and connect again from the Android app
```

### Text Not Appearing on Desktop

1. **Check the desktop app is running** (look for system tray icon)
2. **Verify connection status** in Android app (should show "Connected")
3. **Test with a simple text editor first** (like gedit or kate)
4. **For Wayland users**: Make sure ydotool is installed and running
   ```bash
   sudo apt install ydotool
   systemctl --user enable ydotoold
   systemctl --user start ydotoold
   ```

### Speech Recognition Not Working

1. **Check microphone permissions** in Android Settings > Apps > Prontafon
2. **Test microphone** in Android Settings > Sound to verify it works
3. **Check internet connection** - Android speech recognition may need internet initially
4. **Restart the app**

### Can't Find System Tray Icon

Some desktop environments hide system tray icons by default:

- **GNOME**: Install "AppIndicator Support" extension
- **KDE**: System tray icons work by default
- **XFCE**: Check Panel > Panel Preferences > Items

If you can't see the tray icon, you can still use the app - just control it via the terminal.

## Success Criteria

You'll know it's working when:
- ✅ Desktop app starts without errors
- ✅ System tray icon appears
- ✅ Android app connects successfully
- ✅ Speaking into your phone makes text appear on your desktop
- ✅ Voice commands work correctly

Enjoy seamless voice-to-keyboard dictation!
