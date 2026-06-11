# Release Process

## Overview

Releases are built from `master` branch. Each release produces a signed APK and a git tag.

## Prerequisites

- Android SDK 34+
- JDK 17+
- Keystore file at repository root: `bafangcon.keystore`
- Keystore properties in `keystore.properties`:
  ```properties
  keystore.password=<password>
  key.alias=bafangcon
  key.password=<password>
  ```

## Step-by-Step

### 1. Update version

Edit `version.properties`:

```properties
versionCode=43         # increment by 1
versionName=0.043      # update to match
```

### 2. Update CHANGELOG

Edit `CHANGELOG.md`:

```markdown
## v0.043 - 2026-06-15

### Added
- New feature description

### Changed
- Existing feature change description

### Fixed
- Bug fix description

### Known Issues
- Issue description
```

Move content from "Unreleased" section into the new version. Add empty "Unreleased" section for next cycle.

### 3. Build release APK

Using the project build script:

```powershell
.\build-app.ps1 -Mode release
```

Or manually:

```bash
./gradlew assembleRelease
```

The release APK is at:
```
app/build/outputs/apk/release/app-release.apk
```

### 4. Verify the APK

- Install on test device:
  ```bash
  adb install app/build/outputs/apk/release/app-release.apk
  ```
- Verify:
  - App launches
  - BLE scan works
  - Connection and authentication succeed
  - Ride logging works (if applicable)
  - Assists can be read and written

### 5. Git tag

```bash
git tag -a v0.042 -m "Release v0.042"
git push origin v0.042
```

### 6. GitHub Release

1. Go to https://github.com/anomalyco/bafangcon/releases (adjust URL)
2. Click "Draft a new release"
3. Choose tag `v0.042`
4. Title: `v0.042`
5. Description: Copy relevant section from CHANGELOG.md
6. Attach APK: `app/build/outputs/apk/release/app-release.apk`
7. Publish release

### 7. Post-release

- Verify GitHub Actions CI passes (if configured).
- Update any downstream consumers or documentation.
- Confirm the release APK size is ~2.7 MB (not ~12 MB debug size).

## Build Script

The project includes `build-app.ps1` in the repository root:

```powershell
.\build-app.ps1               # default: debug build
.\build-app.ps1 -Mode debug   # debug build (~12 MB)
.\build-app.ps1 -Mode release # release build (~2.7 MB)
```

Always build release mode unless explicitly asked for debug.

## File Locations

| Artifact | Path |
|---|---|
| Release APK | `app/build/outputs/apk/release/app-release.apk` |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Mapping (ProGuard) | `app/build/outputs/mapping/release/mapping.txt` |
| Version file | `version.properties` |
| Changelog | `CHANGELOG.md` |
| Keystore | `bafangcon.keystore` |
| Keystore config | `keystore.properties` |

## Version History

| Version | Date | Commit/Tag | APK |
|---|---|---|---|
| v0.041 | 2026-06-08 | `v0.041` | `Bafang FT M820 v0.041.apk` |
| v0.042 | 2026-06-08 | `v0.042` | `Bafang FT M820 v0.042.apk` |

## Rollback

If a release has critical issues:

1. Identify the last known-good tag:
   ```bash
   git tag --sort=-v:refname
   ```

2. Create a hotfix branch from the good tag:
   ```bash
   git checkout -b fix/critical-issue v0.040
   ```

3. Fix the issue, commit, build release.

4. Cherry-pick the fix to master:
   ```bash
   git checkout master
   git cherry-pick <fix-commit-hash>
   ```

5. Release as patch version bump (e.g., v0.042 → v0.043).
