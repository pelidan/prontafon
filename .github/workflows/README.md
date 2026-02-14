# GitHub Workflows

This directory contains the GitHub Actions workflows for automating releases of Prontafon.

## Workflows

### `release.yml` - Release Build Workflow

Manually triggered workflow that creates a new release with build artifacts for Linux.

**Trigger:** Manual (`workflow_dispatch`)

**Input:**
- `version` - Semantic version number (e.g., `1.2.3`)

**What it does:**
1. Verifies that the version number in `desktop/Cargo.toml` matches the requested version
2. Creates and pushes a git tag `v{version}`
3. Builds Linux artifacts in parallel:
   - Linux `.deb` package (Debian/Ubuntu)
   - Linux `.rpm` package (Fedora/RHEL)
   - Linux AppImage (universal)
4. Creates a GitHub Release with all artifacts

**Jobs:**
- `prepare` - Validates version and creates tag
- `linux` - Builds .deb, .rpm, and AppImage
- `source` - Creates source tarball
- `release` - Creates GitHub Release with all artifacts

## Setup Instructions

### Prerequisites

Before running the release workflow, ensure the version in `desktop/Cargo.toml` is updated.

### Running the Release Workflow

1. **Navigate to Actions tab** on GitHub

2. **Select "Release Build"** from the workflow list

3. **Click "Run workflow"** button

4. **Enter the version number** (e.g., `1.2.3`)
   - Must match the version in `desktop/Cargo.toml`
   - Must be semantic versioning format: `MAJOR.MINOR.PATCH`
   - Tag will be created as `v1.2.3`

5. **Click "Run workflow"** to start

6. **Monitor the workflow** progress:
   - The workflow takes approximately 10-15 minutes
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
  - `prontafon-{version}-source.tar.gz`

## Local Testing

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

#### Test AppImage Build

```bash
cd desktop/appimage
VERSION=1.2.3 ./build-appimage.sh
```

## Troubleshooting

### Workflow Fails on "Verify version in source files"

**Problem:** The version in `desktop/Cargo.toml` does not match the version entered in the workflow dispatch.

**Solution:** Update `desktop/Cargo.toml` to match the intended release version and commit the change.

### Tag Already Exists Error

**Problem:** Trying to create a release with a version that already exists.

**Solution:** Use a different version number or delete the existing tag if it was created in error.

## Maintenance

### Updating Dependencies

When updating system dependencies (GTK, etc.):

1. Update `desktop/packaging/deb/DEBIAN/control` (Depends line)
2. Update `desktop/packaging/rpm/prontafon.spec` (Requires line)
3. Update workflow `Install system dependencies` step
4. Test builds locally and in CI
