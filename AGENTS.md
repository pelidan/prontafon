# AGENTS.md - Prontafon Developer Guide

## Build & Compile

### Desktop (Linux - Rust)
```bash
cd desktop
cargo build --release    # Release build
cargo build              # Debug build
```
Binary: `desktop/target/release/prontafon-desktop`

### Android (Kotlin)
```bash
cd android/android
./gradlew assembleDebug     # Debug APK
./gradlew assembleRelease   # Release APK
```
APK: `android/android/app/build/outputs/apk/debug/app-debug.apk`

## Run with Debug Script

```bash
./run-prontafon.sh
```

This script:
1. Clears old logs
2. Kills existing desktop instances
3. Checks/builds desktop app if needed (release mode)
4. Starts desktop app with `RUST_LOG=debug`
5. Verifies Android device connection via ADB
6. Installs APK on connected Android device
7. Launches Android app
8. Streams filtered Android logs (Ctrl+C for cleanup & summary)

**Features**:
- Auto-builds desktop binary if missing
- Filters Android logs to show only Prontafon-related components
- Automatic cleanup handler kills desktop app on exit
- Shows session summary with log sizes and analysis commands

**Requires**: Android device connected via ADB

## Log Locations

| Component | Log File |
|-----------|----------|
| Desktop   | `/tmp/prontafon-desktop.log` |
| Android   | `/tmp/prontafon-android.log` |

### Quick Log Commands
```bash
tail -f /tmp/prontafon-desktop.log    # Follow desktop logs
tail -f /tmp/prontafon-android.log    # Follow Android logs
grep -i error /tmp/prontafon-*.log    # Find errors
```
