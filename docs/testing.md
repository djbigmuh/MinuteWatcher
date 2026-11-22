# Testing Strategy

This document describes the testing strategy for the MinuteWatcher project.

## Testing Stack

- **Unit Testing**: JUnit 4
- **Dependency Injection**: Hilt (with Hilt Testing for integration/instrumented tests)
- **Mocking**: Mockito with Mockito-Kotlin
- **UI Testing**:
    - **Jetpack Compose**: Compose Testing APIs (run locally with Robolectric in `src/test` or on device in
      `src/androidTest`)
- **E2E/Macrobenchmark**: UI Automator + Macrobenchmark (in `:macrobenchmark`)
- **Static Analysis**: Detekt + Ktlint
- **Code Coverage**: JaCoCo

## Running Verification & Testing

### Single Verification Command
To run all static analysis checks, unit tests across all modules, and debug app assembly in a single aggregated task, use:
```powershell
$env:ANDROID_PREFS_ROOT=$null; ./gradlew -q verifyQuiet
```

### Summary Output & Diagnosis
- **Successful Run**: Prints a single compact line containing total tests count and report paths, e.g.:
  `VERIFICATION SUCCESS: tests=..., failed=0, reports=[...]`
- **Failed Run**: Prints failure count, failing test names (up to 40), and report paths.
- **Full Output & Reports**: If deeper diagnostics are needed, use persistent HTML/XML reports under `build/reports/tests/...` and `build/test-results/...`. For verbose console logging during investigation, pass `-PverboseTests`:
  ```powershell
  $env:ANDROID_PREFS_ROOT=$null; ./gradlew allUnitTests -PverboseTests
  ```

### Usage Guidelines
- **Clean and Forced Runs**: Actions like `./gradlew clean` or `--rerun-tasks` are expensive and must **only** be used when explicitly necessary to resolve stale caching issues.
- **Logging Levels**: Verbose and debug logging levels (`--info`, `--debug`, `-PverboseTests`) are reserved for active diagnostics and must not be used during routine checks.

### Execution Channels
1. **IDE Build Output Window**: Displays Gradle build progress and task execution summaries. Standard test streams and verbose logs are hidden by default to keep this window clean.
2. **Gradle Launch Console**: Terminal output when executing Gradle commands directly via PowerShell/CLI.
3. **Test Reports**: Persistent HTML and XML test reports containing full captured output, standard streams, test results, and stack traces for every test.

### Local Unit & Compose Tests (Robolectric)

Unit tests and local Compose UI tests run on JVM using Robolectric. They are located in `src/test`.

```powershell
$env:ANDROID_PREFS_ROOT=$null; ./gradlew test
```

### Instrumented Tests (Device)

Instrumented Compose UI tests requiring a real device or emulator are located in `src/androidTest`.

```powershell
$env:ANDROID_PREFS_ROOT=$null; ./gradlew connectedDebugAndroidTest
```

### Static Analysis

```powershell
$env:ANDROID_PREFS_ROOT=$null; ./gradlew detekt ktlintCheck
```

## Robolectric SDK Policy

To maintain test execution speed and minimize overhead, we follow a strict SDK consolidation policy:

- **Default SDK**: Robolectric runs tests against each module's `targetSdk`, which is centrally defined by
  `SdkVersions.TARGET_SDK` in `build-logic`. There is no global Robolectric SDK system property;
  `RobolectricSdkSmokeTest` verifies that the emulated SDK equals the module's `targetSdkVersion`.
  Per-module `robolectric.properties` files are NOT created.
- **No Routine Pinning**: Do NOT add `@Config(sdk = [35])` or any other SDK pinning to test classes if it matches the default.
- **Behavior-Specific Tests**: Pinning a different SDK is ONLY permitted when testing logic that branches based on `Build.VERSION`. In such cases, accompany the `@Config` with a comment: `// behavior-specific SDK`.
- **UI Infrastructure Pinning**: Pinning SDK 35 for Compose UI tests is permitted when required due to test framework
  infrastructure limitations. Accompany the `@Config` with a comment: `// UI-infra SDK pin: <reason>`.
- **Pure Logic**: Tests for classes that do not depend on Android framework (no `android.*` imports) must use the standard JUnit runner, not `RobolectricTestRunner`.

## Pre-Commit Verification

The primary verification command is:

```powershell
$env:ANDROID_PREFS_ROOT=$null; ./gradlew -q verifyQuiet
```
