#!/bin/bash
# Helper script to update version numbers locally
# Usage: ./.github/scripts/update-version.sh 1.2.3

set -e

VERSION="$1"

if [ -z "$VERSION" ]; then
    echo "Usage: $0 <version>"
    echo "Example: $0 1.2.3"
    exit 1
fi

# Validate version format
if ! echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "Error: Version must be in semantic format (e.g., 1.2.3)"
    exit 1
fi

echo "Updating version to $VERSION..."

# Update desktop/Cargo.toml
if [ -f "desktop/Cargo.toml" ]; then
    sed -i "s/^version = \".*\"/version = \"$VERSION\"/" desktop/Cargo.toml
    echo "✓ Updated desktop/Cargo.toml"
else
    echo "✗ desktop/Cargo.toml not found"
    exit 1
fi

# Update android/pubspec.yaml
if [ -f "android/pubspec.yaml" ]; then
    # Keep build number as 1 for local updates
    sed -i "s/^version: .*/version: $VERSION+1/" android/pubspec.yaml
    echo "✓ Updated android/pubspec.yaml"
else
    echo "✗ android/pubspec.yaml not found"
    exit 1
fi

echo ""
echo "Version updated to $VERSION"
echo ""
echo "Changes:"
grep "^version" desktop/Cargo.toml
grep "^version:" android/pubspec.yaml
echo ""
echo "Next steps:"
echo "  1. Review changes: git diff"
echo "  2. Commit: git commit -am 'chore: bump version to $VERSION'"
echo "  3. Tag: git tag -a v$VERSION -m 'Release v$VERSION'"
echo "  4. Push: git push && git push --tags"
