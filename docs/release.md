# Release Checklist and Guidelines

This document describes the environment requirements, signing setup, versioning rules, step-by-step release process, and
release prohibitions for MinuteWatcher.

## 1. Environment Requirements

- **JDK**: JDK 21 (Temurin 21.0.12+) is mandatory for building release artifacts. No other JDK versions (e.g., JDK 25)
  should be present in the system `PATH`.
- **Android SDK**: Must have `compileSdk 37` installed with corresponding build-tools and platform-tools.
- **Gradle Properties**: Machine-specific properties (such as local path overrides) belong in user-level
  `~/.gradle/gradle.properties` and are never committed.

## 2. Release Signing

- **Keystore File**: `release.keystore` located in project root (excluded from VCS via `.gitignore`).
- **Environment Variables**:
    - `MINUTEWATCHER_STORE_PASSWORD`: Keystore store password.
    - `MINUTEWATCHER_KEY_ALIAS`: Key alias.
    - `MINUTEWATCHER_KEY_PASSWORD`: Key password.
- **Signing Requirement**: If any signing parameter or keystore file is missing, release builds will remain unsigned
  (enforced by `app/build.gradle.kts`). Publishing unsigned builds is strictly prohibited.
- **Backup Policy**: `release.keystore` and signing passwords must be backed up securely in offline storage. Loss of the
  signing key permanently prevents updating the application on app stores.

## 3. Versioning Strategy

Configured in `app/build.gradle.kts`:

- `versionCode`: Integer, monotonically increasing with every release.
- `versionName`: SemVer string (e.g. `1.2.0`).

## 4. Release Process

1. **Full Verification**:
   Run full static analysis and unit tests:
   ```powershell
   $env:ANDROID_PREFS_ROOT=$null; ./gradlew ktlintCheck detekt lintDebug testDebugUnitTest :app:assembleDebug
   ```
2. **Baseline Profile Update**:
   Generate updated baseline profile rules:
   ```powershell
   ./gradlew :app:generateReleaseBaselineProfile
   ```
   Commit the generated baseline profile files to the repository.
3. **Version Bump**:
   Increment `versionCode` and update `versionName` in `app/build.gradle.kts`.
4. **Build Artifacts**:
    - Android App Bundle (AAB):
      ```powershell
      ./gradlew :app:bundleRelease
      ```
    - Signed APK:
      ```powershell
      ./gradlew :app:assembleRelease
      ```
5. **Verify Signing**:
   Check APK signature:
   ```powershell
   apksigner verify --print-certs app/build/outputs/apk/release/*.apk
   ```
6. **Smoke Testing**:
   Install and test the release build on a physical device covering key scenarios:
    - Fresh installation and upgrade over previous version.
    - Main screen layout and SIM card stats.
    - Adding and updating home screen widget.
    - Quick Settings tile interaction.
    - Notifications (usage limits and AOD summary).
    - Changing app language via per-app language settings.
    - Device reboot recovery and tracking restoration.
    - Device-to-device data transfer/restore.
7. **Artifact Archiving**:
   Archive release AAB/APK files and ProGuard/R8 mapping files located at `app/build/outputs/mapping/release/` for
   future crash log deobfuscation.

## 5. Release Prohibitions

- **Unsigned Builds**: Releasing or uploading unsigned builds is prohibited.
- **Failed Verification**: Releasing when any check in the verification suite fails is prohibited.
- **SDK Bumps in Release**: Changing `minSdk`, `targetSdk`, or `compileSdk` outside of dedicated dependency upgrade
  tickets is prohibited.
