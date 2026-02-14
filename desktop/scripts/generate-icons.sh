#!/bin/bash

# Generate icon assets for Prontafon

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ICON_DIR="$PROJECT_DIR/resources/icons"

mkdir -p "$ICON_DIR"

# Check for ImageMagick
if ! command -v convert &> /dev/null; then
    echo "ImageMagick is required. Install with: sudo apt install imagemagick"
    exit 1
fi

# Generate simple icon (blue circle with microphone symbol)
# In production, replace with actual icon design

echo "Generating placeholder icons..."

# Create SVG source
cat > "$ICON_DIR/prontafon.svg" << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<svg width="256" height="256" viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="bg" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#4A90D9"/>
      <stop offset="100%" style="stop-color:#2E5B8B"/>
    </linearGradient>
  </defs>
  <circle cx="128" cy="128" r="120" fill="url(#bg)"/>
  <!-- Microphone body -->
  <rect x="104" y="60" width="48" height="80" rx="24" fill="white"/>
  <!-- Microphone stand arc -->
  <path d="M 80 140 Q 80 180 128 180 Q 176 180 176 140" 
        stroke="white" stroke-width="12" fill="none" stroke-linecap="round"/>
  <!-- Stand -->
  <line x1="128" y1="180" x2="128" y2="210" stroke="white" stroke-width="12" stroke-linecap="round"/>
  <line x1="100" y1="210" x2="156" y2="210" stroke="white" stroke-width="12" stroke-linecap="round"/>
</svg>
EOF

# Generate PNG sizes
for size in 16 32 48 64 128 256 512; do
    convert -background none "$ICON_DIR/prontafon.svg" -resize ${size}x${size} "$ICON_DIR/prontafon-${size}.png"
done

# Create standard icon
cp "$ICON_DIR/prontafon-256.png" "$ICON_DIR/prontafon.png"

# Create ICO for potential Windows support
convert "$ICON_DIR/prontafon-16.png" "$ICON_DIR/prontafon-32.png" "$ICON_DIR/prontafon-48.png" "$ICON_DIR/prontafon.ico" 2>/dev/null || true

echo "Icons generated in: $ICON_DIR"
ls -la "$ICON_DIR"
