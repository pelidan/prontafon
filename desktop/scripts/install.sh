#!/bin/bash
set -e

# Prontafon Desktop Installation Script
# Usage: ./install.sh [--user|--system]

INSTALL_MODE="${1:---user}"
VERSION="0.1.0"

echo "=========================================="
echo " Prontafon Desktop Installer"
echo " Version: $VERSION"
echo "=========================================="
echo ""

# Check dependencies
check_dependency() {
    if ! command -v "$1" &> /dev/null; then
        echo "Error: $1 is required but not installed."
        echo "Install it with: $2"
        exit 1
    fi
}

check_dependency "bluetoothctl" "sudo apt install bluez"

# Check display server
if [ -n "$WAYLAND_DISPLAY" ]; then
    echo "Detected: Wayland"
    if ! command -v ydotoold &> /dev/null; then
        echo "Warning: ydotool not found. Install for Wayland support:"
        echo "  sudo apt install ydotool"
        echo "  systemctl --user enable ydotoold"
        echo ""
    fi
else
    echo "Detected: X11"
fi

# Determine installation paths
if [ "$INSTALL_MODE" = "--system" ]; then
    if [ "$EUID" -ne 0 ]; then
        echo "System installation requires root privileges."
        echo "Run with: sudo $0 --system"
        exit 1
    fi
    BIN_DIR="/usr/local/bin"
    DESKTOP_DIR="/usr/share/applications"
    ICON_DIR="/usr/share/icons/hicolor/256x256/apps"
    SYSTEMD_DIR="/etc/systemd/user"
else
    BIN_DIR="$HOME/.local/bin"
    DESKTOP_DIR="$HOME/.local/share/applications"
    ICON_DIR="$HOME/.local/share/icons/hicolor/256x256/apps"
    SYSTEMD_DIR="$HOME/.config/systemd/user"
    AUTOSTART_DIR="$HOME/.config/autostart"
fi

echo "Installation mode: $INSTALL_MODE"
echo "Binary directory: $BIN_DIR"
echo ""

# Create directories
mkdir -p "$BIN_DIR"
mkdir -p "$DESKTOP_DIR"
mkdir -p "$ICON_DIR"
mkdir -p "$SYSTEMD_DIR"
[ -n "$AUTOSTART_DIR" ] && mkdir -p "$AUTOSTART_DIR"

# Determine source directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Build if needed
if [ ! -f "$PROJECT_DIR/target/release/prontafon-desktop" ]; then
    echo "Building release binary..."
    cd "$PROJECT_DIR"
    cargo build --release
fi

# Install binary
echo "Installing binary..."
cp "$PROJECT_DIR/target/release/prontafon-desktop" "$BIN_DIR/"
chmod +x "$BIN_DIR/prontafon-desktop"

# Install desktop file
echo "Installing desktop entry..."
sed "s|Exec=.*|Exec=$BIN_DIR/prontafon-desktop|g" \
    "$PROJECT_DIR/resources/prontafon.desktop" > "$DESKTOP_DIR/prontafon.desktop"

# Install icon
if [ -f "$PROJECT_DIR/resources/icons/prontafon.png" ]; then
    echo "Installing icon..."
    cp "$PROJECT_DIR/resources/icons/prontafon.png" "$ICON_DIR/"
fi

# Install systemd service
echo "Installing systemd service..."
sed "s|ExecStart=.*|ExecStart=$BIN_DIR/prontafon-desktop|g" \
    "$PROJECT_DIR/resources/prontafon.service" > "$SYSTEMD_DIR/prontafon.service"

# Install autostart entry (user mode only)
if [ -n "$AUTOSTART_DIR" ]; then
    echo "Installing autostart entry..."
    cp "$DESKTOP_DIR/prontafon.desktop" "$AUTOSTART_DIR/"
fi

# Update desktop database
if command -v update-desktop-database &> /dev/null; then
    update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
fi

# Reload systemd
systemctl --user daemon-reload 2>/dev/null || true

# Create config directory
mkdir -p "$HOME/.config/prontafon"
mkdir -p "$HOME/.local/share/prontafon"

echo ""
echo "=========================================="
echo " Installation Complete!"
echo "=========================================="
echo ""
echo "Next steps:"
echo ""
echo "1. Start the service:"
echo "   systemctl --user start prontafon"
echo ""
echo "2. Enable autostart:"
echo "   systemctl --user enable prontafon"
echo ""
echo "3. Or run manually:"
echo "   $BIN_DIR/prontafon-desktop"
echo ""
echo "4. Make sure Bluetooth is enabled:"
echo "   sudo systemctl start bluetooth"
echo ""
