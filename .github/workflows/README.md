# GitHub Workflows

This directory contains the GitHub Actions workflows for automating releases of Prontafon.

## Workflows

### `release.yml` - Release Build Workflow

Manually triggered workflow that creates a new release with all build artifacts.

**Trigger:** Manual (`workflow_dispatch`)

**Input:**
- `version` - Semantic version number (e.g., `1.2.3`)

**What it does:**
1. Updates version numbers in `desktop/Cargo.toml` and `android/android/app/build.gradle.kts`
2. Commits the version changes
3. Creates and pushes a git tag `v{version}`
4. Builds all artifacts in parallel:
   - Linux `.deb` package (Debian/Ubuntu)
   - Linux `.rpm` package (Fedora/RHEL)
   - Linux AppImage (universal)
   - Android `.apk` (signed)
   - Source `.tar.gz` archive
5. Creates a GitHub Release with all artifacts

**Jobs:**
- `prepare` - Updates versions and creates tag
- `linux` - Builds .deb, .rpm, and AppImage
- `android` - Builds signed APK
- `source` - Creates source tarball
- `release` - Creates GitHub Release with all artifacts

## Setup Instructions

### Prerequisites

Before running the release workflow, you need to configure the following GitHub Secrets:

#### Android Signing Secrets

1. **Generate a keystore** (if you don't have one):
   ```bash
   cd android/scripts
   ./generate-keystore.sh
   ```

2. **Convert keystore to base64**:
   ```bash
   base64 -w 0 android/app/upload-keystore.jks > keystore.base64
   ```

3. **Add the following secrets** to your GitHub repository (Settings → Secrets and variables → Actions → New repository secret):

   | Secret Name | Value | Description |
   |-------------|-------|-------------|
   | `ANDROID_KEYSTORE_BASE64` | Contents of `keystore.base64` | Base64-encoded keystore file |
   | `ANDROID_KEYSTORE_PASSWORD` | Your keystore password | Password for the keystore |
   | `ANDROID_KEY_ALIAS` | `upload` (or your alias) | Key alias in the keystore |
   | `ANDROID_KEY_PASSWORD` | Your key password | Password for the key |

4. **Verify secrets are set**:
   - Go to your repository on GitHub
   - Navigate to Settings → Secrets and variables → Actions
   - You should see all four secrets listed

### Running the Release Workflow

1. **Navigate to Actions tab** on GitHub

2. **Select "Release Build"** from the workflow list

3. **Click "Run workflow"** button

4. **Enter the version number** (e.g., `1.2.3`)
   - Must be semantic versioning format: `MAJOR.MINOR.PATCH`
   - Tag will be created as `v1.2.3`

5. **Click "Run workflow"** to start

6. **Monitor the workflow** progress:
   - The workflow takes approximately 15-20 minutes
   - You can view logs for each job
   - If any job fails, check the logs for details

7. **Release is created automatically** when all jobs complete successfully

### Workflow Output

After successful completion, you'll have:

- **Git tag**: `v{version}` pushed to repository
- **GitHub Release**: Created at `https://github.com/{owner}/{repo}/releases/tag/v{version}`
- **Artifacts attached to release**:
  - `prontafon-{version}-linux-x86_64.deb`
  - `prontafon-{version}-linux-x86_64.rpm`
  - `Prontafon-{version}-x86_64.AppImage`
  - `prontafon-{version}.apk`
  - `prontafon-{version}-source.tar.gz`

## Local Testing

### Test Version Update Script

```bash
# Update version numbers locally (doesn't commit or tag)
./.github/scripts/update-version.sh 1.2.3

# Review changes
git diff

# Revert if needed
git checkout desktop/Cargo.toml android/pubspec.yaml
```

### Test Package Building Locally

#### Test Debian Package Build

```bash
VERSION="1.2.3"
cd desktop
cargo build --release

# Create package structure
PACKAGE_NAME="prontafon_${VERSION}_amd64"
mkdir -p "${PACKAGE_NAME}/DEBIAN"
mkdir -p "${PACKAGE_NAME}/usr/bin"
mkdir -p "${PACKAGE_NAME}/usr/share/applications"

# Copy files
cp target/release/prontafon-desktop "${PACKAGE_NAME}/usr/bin/"
cp resources/prontafon.desktop "${PACKAGE_NAME}/usr/share/applications/"
cp packaging/deb/DEBIAN/control "${PACKAGE_NAME}/DEBIAN/"
sed -i "s/VERSION/${VERSION}/" "${PACKAGE_NAME}/DEBIAN/control"

# Build package
dpkg-deb --build "${PACKAGE_NAME}"
```

#### Test Android Build

```bash
cd android/android
./gradlew dependencies
./gradlew test
./gradlew assembleRelease
```

#### Test AppImage Build

```bash
cd desktop/appimage
VERSION=1.2.3 ./build-appimage.sh
```

## Troubleshooting

### Workflow Fails on "Setup Android keystore"

**Problem:** Missing or incorrect Android signing secrets

**Solution:**
1. Verify all four secrets are set in GitHub (see Setup Instructions above)
2. Check that keystore password and key password are correct
3. Ensure base64 encoding was done correctly (no line breaks)

### Workflow Fails on "Build release binary"

**Problem:** Rust compilation errors or missing dependencies

**Solution:**
1. Test build locally: `cd desktop && cargo build --release`
2. Check for recent changes that might have broken the build
3. Review the workflow logs for specific error messages

### Tag Already Exists Error

**Problem:** Trying to create a release with a version that already exists

**Solution:**
1. Use a different version number
2. Or delete the existing tag:
   ```bash
   git tag -d v1.2.3
   git push origin :refs/tags/v1.2.3
   ```

### APK Not Signed

**Problem:** APK builds but isn't signed properly

**Solution:**
1. Check that `android/key.properties` is created correctly in the workflow
2. Verify signing configuration in `android/app/build.gradle`
3. Test signing locally with your keystore

### .deb Package Install Fails

**Problem:** Missing dependencies or incorrect package structure

**Solution:**
1. Check `desktop/packaging/deb/DEBIAN/control` for correct dependencies
2. Test package locally:
   ```bash
   dpkg -i prontafon_1.2.3_amd64.deb
   apt-get install -f  # Install missing dependencies
   ```

## Maintenance

### Updating Dependencies

When updating system dependencies (GTK, libadwaita, etc.):

1. Update `desktop/packaging/deb/DEBIAN/control` (Depends line)
2. Update `desktop/packaging/rpm/prontafon.spec` (Requires line)
3. Update workflow `Install system dependencies` step
4. Test builds locally and in CI

### Updating Rust Toolchain

The workflow uses the latest stable Rust. To pin a specific version:

Edit `.github/workflows/release.yml`:
```yaml
- name: Setup Rust
  uses: dtolnay/rust-toolchain@stable
  # Change to specific version:
  # uses: dtolnay/rust-toolchain@1.75.0
```

## Release Checklist

Before triggering a release:

- [ ] All tests passing locally
- [ ] Version number decided (follow semantic versioning)
- [ ] CHANGELOG.md updated (if exists)
- [ ] Android keystore secrets configured in GitHub
- [ ] No uncommitted changes
- [ ] All PRs merged to main branch

After release:

- [ ] Test installation on Ubuntu/Debian
- [ ] Test installation on Fedora/RHEL
- [ ] Test AppImage on different distro
- [ ] Test Android APK installation
- [ ] Verify all download links work
- [ ] Announce release (if applicable)

## Support

For issues with the GitHub workflows:
- Check the [Actions tab](../../actions) for detailed logs
- Review error messages in failed jobs
- Open an issue if you need help

For general project issues:
- See the main [README.md](../../README.md)
- Check existing [Issues](../../issues)
