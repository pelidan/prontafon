#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=========================================="
echo " Prontafon Android Release Build"
echo "=========================================="
echo ""

cd "$PROJECT_DIR"

# Check for key.properties
if [ ! -f "android/key.properties" ]; then
    echo "Error: android/key.properties not found"
    echo "Run ./scripts/generate-keystore.sh first"
    exit 1
fi

# Clean previous builds
echo "Cleaning previous builds..."
flutter clean

# Get dependencies
echo "Getting dependencies..."
flutter pub get

# Run tests
echo "Running tests..."
flutter test || {
    echo "Tests failed! Continue anyway? (y/N)"
    read confirm
    if [ "$confirm" != "y" ]; then
        exit 1
    fi
}

# Build release APK
echo ""
echo "Building release APK..."
flutter build apk --release

# Build App Bundle (for Play Store)
echo ""
echo "Building App Bundle..."
flutter build appbundle --release

echo ""
echo "=========================================="
echo " Build Complete!"
echo "=========================================="
echo ""
echo "APK: $PROJECT_DIR/build/app/outputs/flutter-apk/app-release.apk"
echo "AAB: $PROJECT_DIR/build/app/outputs/bundle/release/app-release.aab"
echo ""

# Show APK info
if command -v aapt &> /dev/null; then
    echo "APK Info:"
    aapt dump badging "$PROJECT_DIR/build/app/outputs/flutter-apk/app-release.apk" | grep -E "package:|versionCode|versionName"
fi
