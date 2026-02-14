#!/bin/bash

# Prontafon - Rebuild Desktop + Android from Scratch
# Cleans old build artifacts and rebuilds both applications for deployment

set -e  # Exit on error

# Auto-detect project root (script's directory)
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_BIN="$PROJECT_ROOT/desktop/target/release/prontafon-desktop"
ANDROID_APK="$PROJECT_ROOT/android/android/app/build/outputs/apk/debug/app-debug.apk"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo "================================================"
echo "  Prontafon - Clean Rebuild Script"
echo "================================================"
echo ""

# Step 1: Clean desktop build artifacts
echo -e "${YELLOW}[1/5]${NC} Cleaning desktop build artifacts..."
cd "$PROJECT_ROOT/desktop"
cargo clean
echo "  ✓ Desktop artifacts cleaned"
echo ""

# Step 2: Clean Android build artifacts
echo -e "${YELLOW}[2/5]${NC} Cleaning Android build artifacts..."
cd "$PROJECT_ROOT/android/android"
./gradlew clean
echo "  ✓ Android artifacts cleaned"
echo ""

# Step 3: Rebuild desktop (release mode)
echo -e "${YELLOW}[3/5]${NC} Rebuilding desktop app (release mode)..."
cd "$PROJECT_ROOT/desktop"
cargo build --release
if [ -f "$DESKTOP_BIN" ]; then
    echo "  ✓ Desktop build complete"
else
    echo -e "${RED}  ✗ Desktop build failed!${NC}"
    exit 1
fi
echo ""

# Step 4: Rebuild Android (debug APK)
echo -e "${YELLOW}[4/5]${NC} Rebuilding Android app (debug APK)..."
cd "$PROJECT_ROOT/android/android"
./gradlew assembleDebug
if [ -f "$ANDROID_APK" ]; then
    echo "  ✓ Android build complete"
else
    echo -e "${RED}  ✗ Android build failed!${NC}"
    exit 1
fi
echo ""

# Step 5: Print summary
echo -e "${YELLOW}[5/5]${NC} Build summary..."
echo ""
echo -e "${GREEN}================================================${NC}"
echo -e "${GREEN}  ✓ Rebuild Complete - Ready for Deployment${NC}"
echo -e "${GREEN}================================================${NC}"
echo ""
echo -e "${BLUE}Desktop Binary:${NC}"
echo "  Path:  $DESKTOP_BIN"
echo "  Size:  $(du -h "$DESKTOP_BIN" | cut -f1)"
echo ""
echo -e "${BLUE}Android APK:${NC}"
echo "  Path:  $ANDROID_APK"
echo "  Size:  $(du -h "$ANDROID_APK" | cut -f1)"
echo ""
echo -e "${GREEN}Fresh packages ready for deployment!${NC}"
echo ""
echo "Next steps:"
echo "  • Run './run-prontafon.sh' to test both apps"
echo "  • Deploy desktop binary: $DESKTOP_BIN"
echo "  • Deploy Android APK:    $ANDROID_APK"
echo ""
