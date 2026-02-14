#!/bin/bash

# Prontafon - Run Desktop + Mobile with Debug Logging
# Clears logs, starts desktop app, installs & launches Android app, captures logs

set -e  # Exit on error

# Auto-detect project root (script's directory)
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_LOG="/tmp/prontafon-desktop.log"
ANDROID_LOG="/tmp/prontafon-android.log"
DESKTOP_BIN="$PROJECT_ROOT/desktop/target/release/prontafon-desktop"
ANDROID_APK="$PROJECT_ROOT/android/android/app/build/outputs/apk/debug/app-debug.apk"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo "================================================"
echo "  Prontafon - Debug Session Launcher"
echo "================================================"
echo ""

# Step 1: Clear old logs
echo -e "${YELLOW}[1/8]${NC} Clearing old log files..."
rm -f "$DESKTOP_LOG" "$ANDROID_LOG"
echo "  ✓ Logs cleared"
echo ""

# Step 2: Kill any existing desktop app instances
echo -e "${YELLOW}[2/8]${NC} Checking for existing desktop app instances..."
if pgrep -f "prontafon-desktop" > /dev/null; then
    echo "  Found running instance(s), killing..."
    pkill -f "prontafon-desktop"
    sleep 1
    echo "  ✓ Old instances killed"
else
    echo "  ✓ No existing instances found"
fi
echo ""

# Step 3: Check/Build desktop app
echo -e "${YELLOW}[3/8]${NC} Checking desktop app..."
cd "$PROJECT_ROOT/desktop"
if [ -f "$DESKTOP_BIN" ]; then
    echo "  ✓ Using existing release binary"
else
    echo "  Building desktop app (release mode)..."
    cargo build --release
    echo "  ✓ Build complete"
fi
echo ""

# Step 4: Start desktop app with logging
echo -e "${YELLOW}[4/8]${NC} Starting desktop app with debug logging..."
echo "  Log file: $DESKTOP_LOG"
RUST_LOG=debug "$DESKTOP_BIN" > "$DESKTOP_LOG" 2>&1 &
DESKTOP_PID=$!
echo "  ✓ Desktop app started (PID: $DESKTOP_PID)"
echo "  Waiting 3 seconds for Bluetooth initialization..."
sleep 3

# Verify desktop app is still running
if ! ps -p $DESKTOP_PID > /dev/null; then
    echo -e "${RED}  ✗ Desktop app failed to start!${NC}"
    echo "  Check logs: tail -20 $DESKTOP_LOG"
    exit 1
fi
echo "  ✓ Desktop app running successfully"
echo ""

# Step 5: Check if phone is connected
echo -e "${YELLOW}[5/8]${NC} Checking for connected Android device..."
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}  ✗ No Android device detected!${NC}"
    echo "  Please connect your phone with USB debugging enabled."
    kill $DESKTOP_PID 2>/dev/null
    exit 1
fi
DEVICE_ID=$(adb devices | grep "device$" | awk '{print $1}')
echo "  ✓ Device found: $DEVICE_ID"
echo ""

# Step 6: Install Android APK
echo -e "${YELLOW}[6/8]${NC} Installing Android APK..."
if [ ! -f "$ANDROID_APK" ]; then
    echo -e "${RED}  ✗ APK not found: $ANDROID_APK${NC}"
    echo "  Please build the Android app first."
    kill $DESKTOP_PID 2>/dev/null
    exit 1
fi
# Uninstall old version first to avoid corruption
if adb shell pm list packages | grep -q "com.prontafon"; then
    echo "  Uninstalling old version..."
    adb uninstall com.prontafon > /dev/null 2>&1 || true
fi
adb install "$ANDROID_APK" 2>&1 | grep -E "Success|Performing"
echo "  ✓ APK installed"
echo ""

# Step 7: Launch Android app
echo -e "${YELLOW}[7/8]${NC} Launching Prontafon on device..."
adb logcat -c  # Clear old logs
adb shell am start -n com.prontafon/.MainActivity > /dev/null 2>&1
sleep 2
echo "  ✓ App launched"
echo ""

# Step 8: Start capturing Android logs
echo -e "${YELLOW}[8/8]${NC} Capturing Android logs..."
echo "  Log file: $ANDROID_LOG"
echo ""
echo -e "${GREEN}================================================${NC}"
echo -e "${GREEN}  ✓ Both apps running with debug logging${NC}"
echo -e "${GREEN}================================================${NC}"
echo ""
echo -e "${BLUE}Desktop:${NC}"
echo "  PID:     $DESKTOP_PID"
echo "  Log:     $DESKTOP_LOG"
echo ""
echo -e "${BLUE}Android:${NC}"
echo "  Device:  $DEVICE_ID"
echo "  Log:     $ANDROID_LOG"
echo ""
echo -e "${YELLOW}Press Ctrl+C to stop logging and exit${NC}"
echo -e "${YELLOW}(Desktop app will be killed automatically)${NC}"
echo ""
echo "Streaming Android logs..."
echo "========================================"
echo ""

# Trap Ctrl+C to cleanup and show summary
cleanup_and_exit() {
    echo ""
    echo ""
    echo "========================================"
    echo "Stopping..."
    echo ""
    
    # Kill desktop app
    if ps -p $DESKTOP_PID > /dev/null 2>&1; then
        echo "Killing desktop app (PID: $DESKTOP_PID)..."
        kill $DESKTOP_PID 2>/dev/null
        sleep 1
        # Force kill if still running
        if ps -p $DESKTOP_PID > /dev/null 2>&1; then
            kill -9 $DESKTOP_PID 2>/dev/null
        fi
        echo "✓ Desktop app stopped"
    fi
    
    echo ""
    echo "================================================"
    echo "  Session Summary"
    echo "================================================"
    echo ""
    echo "Log files preserved at:"
    echo "  Desktop: $DESKTOP_LOG ($(du -h "$DESKTOP_LOG" 2>/dev/null | cut -f1 || echo "0"))"
    echo "  Android: $ANDROID_LOG ($(du -h "$ANDROID_LOG" 2>/dev/null | cut -f1 || echo "0"))"
    echo ""
    echo "Quick analysis commands:"
    echo "  # View all logs"
    echo "  tail -100 $DESKTOP_LOG"
    echo "  tail -100 $ANDROID_LOG"
    echo ""
    echo "  # Find errors"
    echo "  grep -i error $DESKTOP_LOG"
    echo "  grep -i error $ANDROID_LOG"
    echo ""
    echo "  # Find connection events"
    echo "  grep -i 'connect\|pair\|scan' $DESKTOP_LOG $ANDROID_LOG"
    echo ""
    echo "  # Monitor both files"
    echo "  tail -f $DESKTOP_LOG $ANDROID_LOG"
    echo ""
    
    exit 0
}

trap cleanup_and_exit INT TERM

# Start capturing Android logs (this will run until Ctrl+C)
adb logcat | grep --line-buffered -E "com.prontafon|Prontafon|BleManager|BleConnection|BleScanner|BleCharacteristic|SpeechRecognition|SpeechService|BeepSuppressor|HomeViewModel|ConnectionViewModel|CryptoManager|MessageEncryption|MainActivity" | tee "$ANDROID_LOG"
