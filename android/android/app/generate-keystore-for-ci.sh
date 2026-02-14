#!/bin/bash

# Generate release keystore for Prontafon Android app (for GitHub Actions)

KEYSTORE_FILE="upload-keystore.jks"
KEY_ALIAS="upload"

echo "=========================================="
echo " Prontafon Keystore Generator"
echo " (GitHub Actions Compatible)"
echo "=========================================="
echo ""

if [ -f "$KEYSTORE_FILE" ]; then
    echo "Keystore already exists: $KEYSTORE_FILE"
    read -p "Overwrite? (y/N): " confirm
    if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
        echo "Aborted."
        exit 0
    fi
fi

echo ""
echo "This will generate a keystore for signing Android releases."
echo "You'll need to provide this information for GitHub Secrets."
echo ""
echo "Enter keystore information:"
echo ""

read -p "Organization Name: " ORG_NAME
read -p "City/Locality: " CITY
read -p "State/Province: " STATE
read -p "Country Code (2 letters, e.g., US): " COUNTRY

echo ""
echo "Enter passwords (minimum 6 characters):"
echo "IMPORTANT: Save these securely - you'll need them for GitHub Secrets!"
echo ""
read -sp "Keystore Password: " STORE_PASS
echo ""
read -sp "Confirm Keystore Password: " STORE_PASS2
echo ""

if [ "$STORE_PASS" != "$STORE_PASS2" ]; then
    echo "Error: Passwords do not match"
    exit 1
fi

read -sp "Key Password: " KEY_PASS
echo ""
read -sp "Confirm Key Password: " KEY_PASS2
echo ""

if [ "$KEY_PASS" != "$KEY_PASS2" ]; then
    echo "Error: Passwords do not match"
    exit 1
fi

echo ""
echo "Generating keystore..."

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

if [ $? -ne 0 ]; then
    echo "Error: Failed to generate keystore"
    exit 1
fi

echo ""
echo "✅ Keystore created successfully!"
echo ""
echo "=========================================="
echo " SAVE THESE VALUES FOR GITHUB SECRETS"
echo "=========================================="
echo ""
echo "File: $PWD/$KEYSTORE_FILE"
echo ""
echo "GitHub Secrets to configure:"
echo "  ANDROID_KEYSTORE_BASE64  = (run: base64 -w 0 $KEYSTORE_FILE)"
echo "  ANDROID_KEYSTORE_PASSWORD = $STORE_PASS"
echo "  ANDROID_KEY_ALIAS        = $KEY_ALIAS"
echo "  ANDROID_KEY_PASSWORD     = $KEY_PASS"
echo ""
echo "=========================================="
echo ""
echo "⚠️  IMPORTANT SECURITY NOTES:"
echo "  1. NEVER commit $KEYSTORE_FILE to git"
echo "  2. BACKUP this keystore file securely"
echo "  3. Keep passwords in a password manager"
echo "  4. Without this keystore, you cannot update the app!"
echo ""
echo "Next step: Run the following to encode for GitHub:"
echo "  base64 -w 0 $KEYSTORE_FILE > keystore.base64"
echo ""
