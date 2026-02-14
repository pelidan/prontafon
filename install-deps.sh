#!/bin/bash
# Prontafon - Install System Dependencies
# Run with: sudo ./install-deps.sh

set -e

echo "Installing system development libraries for Prontafon..."

apt update

apt install -y \
    libgtk-4-dev \
    libadwaita-1-dev \
    libdbus-1-dev \
    libsqlite3-dev \
    libbluetooth-dev \
    libgtk-3-dev \
    clang \
    cmake \
    ninja-build \
    pkg-config \
    build-essential

echo ""
echo "Done! All dependencies installed."
echo "Run 'flutter doctor' to verify Flutter setup."
