#!/bin/bash

# Generate release keystore for Prontafon Android app

KEYSTORE_DIR="$HOME/.android/keystores"
KEYSTORE_FILE="$KEYSTORE_DIR/prontafon.jks"
KEY_ALIAS="prontafon"

echo "=========================================="
echo " Prontafon Keystore Generator"
echo "=========================================="
echo ""

if [ -f "$KEYSTORE_FILE" ]; then
    echo "Keystore already exists at: $KEYSTORE_FILE"
    read -p "Overwrite? (y/N): " confirm
    if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
        echo "Aborted."
        exit 0
    fi
fi

mkdir -p "$KEYSTORE_DIR"

echo ""
echo "Enter keystore information:"
echo ""

read -p "Organization Name: " ORG_NAME
read -p "City/Locality: " CITY
read -p "State/Province: " STATE
read -p "Country Code (2 letters): " COUNTRY

echo ""
echo "Enter passwords (minimum 6 characters):"
read -sp "Keystore Password: " STORE_PASS
echo ""
read -sp "Key Password: " KEY_PASS
echo ""

# Generate keystore
keytool -genkey -v \
    -keystore "$KEYSTORE_FILE" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$STORE_PASS" \
    -keypass "$KEY_PASS" \
    -dname "CN=Prontafon, O=$ORG_NAME, L=$CITY, ST=$STATE, C=$COUNTRY"

echo ""
echo "Keystore created at: $KEYSTORE_FILE"
echo ""

# Create key.properties
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
KEY_PROPS="$PROJECT_DIR/android/key.properties"

cat > "$KEY_PROPS" << EOF
storePassword=$STORE_PASS
keyPassword=$KEY_PASS
keyAlias=$KEY_ALIAS
storeFile=$KEYSTORE_FILE
EOF

echo "key.properties created at: $KEY_PROPS"
echo ""
echo "IMPORTANT: Add these files to your backup:"
echo "  - $KEYSTORE_FILE"
echo "  - $KEY_PROPS"
echo ""
echo "DO NOT commit key.properties to git!"
