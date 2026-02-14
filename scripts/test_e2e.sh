#!/bin/bash

# End-to-end test script for Prontafon
# Run this after starting both apps

set -e

echo "=== Prontafon E2E Test ==="
echo ""

# Check if Linux app is running
if ! pgrep -f "prontafon-desktop" > /dev/null; then
    echo "ERROR: Linux desktop app is not running"
    echo "Start it with: cd desktop && cargo run"
    exit 1
fi

echo "✓ Linux app is running"

# Check Bluetooth
if ! hciconfig | grep -q "UP RUNNING"; then
    echo "ERROR: Bluetooth is not enabled"
    exit 1
fi

echo "✓ Bluetooth is enabled"

# Check if RFCOMM is listening
if ! ss -l | grep -q "rfcomm"; then
    echo "WARNING: RFCOMM listener may not be active"
fi

echo ""
echo "Manual test steps:"
echo ""
echo "1. On Android:"
echo "   - Open Prontafon app"
echo "   - Tap 'Connect' or Bluetooth icon"
echo "   - Select your computer from the list"
echo "   - Enter a 6-digit PIN when prompted"
echo ""
echo "2. On Linux:"
echo "   - A PIN dialog should appear"
echo "   - Enter the same 6-digit PIN"
echo ""
echo "3. After pairing:"
echo "   - The connection status should show 'Connected'"
echo "   - Tap the microphone and speak"
echo "   - Text should appear at cursor on Linux"
echo ""
echo "4. Test commands:"
echo "   - Say 'Hello world new line'"
echo "   - Verify text is typed and Enter is pressed"
echo ""
echo "5. Test voice commands:"
echo "   - Open a text editor on Linux"
echo "   - Type some text manually"
echo "   - Say 'select all'"
echo "   - Say 'copy that'"
echo "   - Say 'paste'"
echo ""

read -p "Press Enter when ready to verify results..."

echo ""
echo "Verification checklist:"
echo "[ ] Connection established"
echo "[ ] PIN pairing completed"
echo "[ ] Text transmitted correctly"
echo "[ ] Commands executed correctly"
echo "[ ] Reconnection works after disconnect"
echo ""

echo "Test complete!"
