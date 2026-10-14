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

## Running Tests

### Local Unit & Compose Tests (Robolectric)

Unit tests and local Compose UI tests run on JVM using Robolectric. They are located in `src/test`.

```bash
$env:ANDROID_PREFS_ROOT=$null; ./gradlew test
```

### Instrumented Tests (Device)

Instrumented Compose UI tests requiring a real device or emulator are located in `src/androidTest`.

```bash
$env:ANDROID_PREFS_ROOT=$null; ./gradlew connectedDebugAndroidTest
```

### Static Analysis

```bash
$env:ANDROID_PREFS_ROOT=$null; ./gradlew detekt ktlintCheck
```

## Robolectric SDK Policy

To maintain test execution speed and minimize overhead, we follow a strict SDK consolidation policy:

- **Default SDK**: Set globally to **37** via a Gradle system property (`systemProperty("robolectric.sdk", "37")`),
  verified by `RobolectricSdkSmokeTest`. Per-module `robolectric.properties` files are NOT created.
- **No Routine Pinning**: Do NOT add `@Config(sdk = [35])` or any other SDK pinning to test classes if it matches the default.
- **Behavior-Specific Tests**: Pinning a different SDK is ONLY permitted when testing logic that branches based on `Build.VERSION`. In such cases, accompany the `@Config` with a comment: `// behavior-specific SDK`.
- **UI Infrastructure Pinning**: Pinning SDK 35 for Compose UI tests is permitted when required due to test framework
  infrastructure limitations. Accompany the `@Config` with a comment: `// UI-infra SDK pin: <reason>`.
- **Pure Logic**: Tests for classes that do not depend on Android framework (no `android.*` imports) must use the standard JUnit runner, not `RobolectricTestRunner`.

## Pre-Commit Verification

The following command must pass locally before completing a task and committing:

```bash
$env:ANDROID_PREFS_ROOT=$null; ./gradlew ktlintCheck detekt lintDebug testDebugUnitTest :app:assembleDebug
```
