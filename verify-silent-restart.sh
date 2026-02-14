#!/bin/bash

# Silent Restart Loop Verification Script
# Monitors Android logs for Golden Path timing events

echo "=================================================="
echo "Silent Restart Loop - Golden Path Verification"
echo "=================================================="
echo ""
echo "This script monitors for the 4 key timing events:"
echo "  T+0ms   → Mute"
echo "  T+50ms  → Cancel & Destroy"
echo "  T+100ms → Create & Start"
echo "  T+350ms → Unmute"
echo ""
echo "Press Ctrl+C to stop monitoring"
echo ""

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device connected via ADB"
    exit 1
fi

echo "✅ Device connected, starting log monitor..."
echo ""

# Clear old logs
adb logcat -c

# Monitor specific log patterns
adb logcat -s SpeechRecognitionMgr:D BeepSuppressor:D | grep --line-buffered -E \
    "muteStreams|Mute propagated|Canceled and destroyed|Started listening|Unmuted after beep" | \
    while IFS= read -r line; do
        # Extract timestamp
        timestamp=$(echo "$line" | awk '{print $2}')
        
        # Colorize output based on event
        if echo "$line" | grep -q "muteStreams"; then
            echo -e "\033[1;33m[T+0ms  ] MUTE\033[0m → $line"
        elif echo "$line" | grep -q "Mute propagated"; then
            delay=$(echo "$line" | grep -oP '\(\K[0-9]+(?=ms\))' || echo "?")
            echo -e "\033[1;34m[T+${delay}ms] PROPAGATE\033[0m → $line"
        elif echo "$line" | grep -q "Canceled and destroyed"; then
            echo -e "\033[1;31m[T+50ms ] CANCEL & DESTROY\033[0m → $line"
        elif echo "$line" | grep -q "Started listening"; then
            echo -e "\033[1;32m[T+100ms] CREATE & START\033[0m → $line"
        elif echo "$line" | grep -q "Unmuted after beep"; then
            delay=$(echo "$line" | grep -oP '\(\K[0-9]+(?=ms\))' || echo "?")
            echo -e "\033[1;35m[T+${delay}ms] UNMUTE\033[0m → $line"
        else
            echo "$line"
        fi
    done
