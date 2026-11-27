# Release Checklist and Guidelines

This document describes the environment requirements, signing setup, versioning rules, step-by-step release process,
clean-clone build verification, store publishing procedures, and release prohibitions for MinuteWatcher.

## 1. Environment Requirements

- **JDK**: JDK 21 (Temurin 21.0.12+) is mandatory for building release artifacts. No other JDK versions (e.g., JDK 25)
  should be present in the system `PATH`.
- **Android SDK**: Must have `compileSdk 37.1` installed with corresponding build-tools and platform-tools.
- **Gradle Properties**: Machine-specific properties (such as local path overrides) belong in user-level
  `~/.gradle/gradle.properties` and are never committed.

## 2. Release Signing

- **Keystore File**: `app/release.keystore` located in the `app` module directory (excluded from VCS via `.gitignore`).
- **Local Configuration**: An optional root-level `signing.properties` may hold `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. This file is ignored by Git and must never be committed. Use `app/release.keystore` for the default keystore location, or set `storeFile=app/release.keystore` explicitly.
- **Environment Variables**: The following variables override matching values from the local properties file, making CI-provided secrets authoritative:
    - `MINUTEWATCHER_STORE_FILE`: Optional path to the keystore (defaults to `app/release.keystore`).
    - `MINUTEWATCHER_STORE_PASSWORD`: Keystore store password.
    - `MINUTEWATCHER_KEY_ALIAS`: Key alias.
    - `MINUTEWATCHER_KEY_PASSWORD`: Key password.
- **Signing Requirement**: Release build tasks fail fast if any signing parameter or the keystore file is missing
  (enforced by `app/build.gradle.kts`). Configure signing before running `bundleRelease` or `assembleRelease`.
- **Backup Policy**: `release.keystore` and signing passwords must be backed up securely in offline storage. Loss of the
  signing key permanently prevents updating the application on app stores.

## 3. Versioning Strategy

Configured in `app/build.gradle.kts`:

- `versionCode`: Integer, monotonically increasing with every release.
- `versionName`: SemVer string (e.g. `4.3.0`).
- **Changelog Requirement**: With every `versionCode` increment in `app/build.gradle.kts`, a corresponding changelog file
  `fastlane/metadata/android/en-US/changelogs/<new versionCode>.txt` (and Russian translation in `ru/`) must be created
  prior to building release artifacts.

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
   Increment `versionCode` and update `versionName` in `app/build.gradle.kts`. Create matching fastlane changelogs.
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

## 5. Clean-Clone Build Verification

To ensure readiness for automated build systems (such as F-Droid build servers and CI pipelines):

- **Tracked Build Wrapper Files**: The repository explicitly tracks all required build wrappers in git: `gradlew`,
  `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties`.
- **Exclusion of Secrets**: Local secrets and signing files (`release.keystore`, `local.properties`, `google-services.json`)
  are strictly excluded from VCS via `.gitignore`.
- **Clean-Clone Verification Test**: Verification in a clean clone confirms that building unsigned debug artifacts
  (`.\gradlew.bat :app:assembleDebug` with JDK 21) succeeds out of the box without requiring uncommitted files.

## 6. Publishing to Google Play

1. **Artifact**: Upload signed Android App Bundle (AAB) generated via `./gradlew :app:bundleRelease`.
2. **Play App Signing**: Configure Google Play App Signing using an upload key. Local signing scheme in `app/build.gradle.kts`
   is compliant with Play console upload requirements.
3. **Console Declarations Checklist**:
    - **Data Safety**: Declare zero data collection and zero data sharing, aligned with `PRIVACY_POLICY.md`.
    - **Content Rating**: Complete IARC questionnaire for utility application without user-generated content.
    - **Target Audience**: Select 13+ or general audience; declare no child-directed content.
    - **Privacy Policy URL**: Link to hosted policy on GitHub Pages (`https://djbigmuh.github.io/MinuteWatcher/PRIVACY_POLICY.md` or repository `/docs`).
4. **Listing Metadata Assets**: Use localized fastlane metadata from `fastlane/metadata/android/`:
    - App Icon: `en-US/images/icon.png` (512×512 px).
    - Feature Graphic: `en-US/images/featureGraphic.png` (1024×500 px).
    - Screenshots: Follow guidelines in `fastlane/metadata/android/en-US/images/phoneScreenshots/README.md`.
5. **Rollout Tracks**: Deploy sequentially through `Internal testing` → `Closed testing` → `Production` staged rollout.

## 7. Publishing to F-Droid

1. **Prerequisites**:
    - Public git repository with release commit tagged as `v<versionName>` (e.g. `v4.3.0`).
    - Gradle wrapper tracked in VCS.
    - Zero proprietary closed-source SDKs or binary blobs.
    - Store metadata organized in `fastlane/metadata/android/`.
2. **Inclusion Procedure**:
    - Submit an RFP (Request For Packaging) issue on `fdroiddata` issue tracker, or open a Merge Request with build recipe
      in `https://gitlab.com/fdroid/fdroiddata`.
    - Build recipe parameters:
      ```yaml
      Categories:
        - System
      License: MIT
      SourceCode: https://github.com/djbigmuh/MinuteWatcher
      IssueTracker: https://github.com/djbigmuh/MinuteWatcher/issues
      AutoName: MinuteWatcher
      RepoType: git
      Repo: https://github.com/djbigmuh/MinuteWatcher.git
      Builds:
        - versionName: '4.3.0'
          versionCode: 413
          commit: v4.3.0
          gradle:
            - yes
      ```
3. **Reproducibility & Build Environment**:
   F-Droid builds artifacts in a reproducible Debian container environment using JDK 21 (Temurin 21.0.12+),
   Gradle wrapper, and official Android SDK `compileSdk 37.1`. Releasing only artifacts built in this environment is
   recommended.
4. **Automated Updates**: Once the recipe is merged, F-Droid automatically detects new release tags matching `v*`
   and builds updated APKs.

## 8. Publishing to GitHub Releases

1. **Tagging**: Create an annotated git tag on the release commit:
   ```powershell
   git tag -a v4.3.0 -m "Release v4.3.0"
   git push origin v4.3.0
   ```
2. **Release Artifacts**:
    - Universal signed release APK (`app/build/outputs/apk/release/app-release.apk` generated via `./gradlew :app:assembleRelease`).
    - Checksums file `SHA256SUMS.txt` generated via PowerShell:
      ```powershell
      Get-FileHash -Algorithm SHA256 app/build/outputs/apk/release/*.apk | Format-List > SHA256SUMS.txt
      ```
    - Release notes text copied from `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. **Release Restrictions**:
    - **Do NOT upload AAB files** to GitHub Releases (AAB is intended for app store distribution).
    - **Do NOT upload R8 mapping files** (`app/build/outputs/mapping/release/`) to public releases — archive them privately per Section 4 Step 7.
    - **Verification**: Always verify APK signature before uploading (`apksigner verify --print-certs app/build/outputs/apk/release/*.apk`).

## 9. Release Prohibitions

- **Unsigned Builds**: Releasing or uploading unsigned builds is prohibited.
- **Failed Verification**: Releasing when any check in the verification suite fails is prohibited.
- **SDK Bumps in Release**: Changing `minSdk`, `targetSdk`, or `compileSdk` outside of dedicated dependency upgrade
  tickets is prohibited.
