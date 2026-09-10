# Testing Strategy

This document describes the testing strategy for the MinuteWatcher project.

## Testing Stack

- **Unit Testing**: JUnit 4
- **Dependency Injection**: Hilt (with Hilt Testing for integration/instrumented tests)
- **Mocking**: Mockito with Mockito-Kotlin
- **UI Testing**:
    - **Jetpack Compose**: Compose Testing APIs (run locally with Robolectric or on device)
    - **Views**: Espresso
- **Screenshot Testing**: Compose Preview Screenshot Testing (Experimental)
- **E2E/Macrobenchmark**: UI Automator + Macrobenchmark
- **Static Analysis**: Detekt + Ktlint
- **Code Coverage**: JaCoCo

## Running Tests

### Local Unit Tests

Run all unit tests in the project:

```bash
$env:ANDROID_PREFS_ROOT=$null; ./gradlew test
```

### UI Tests (Robolectric)

UI tests for Compose and Views can run locally using Robolectric. They are located in `src/test`.

```bash
$env:ANDROID_PREFS_ROOT=$null; ./gradlew :feature:settings:testDebugUnitTest
```

### Instrumented Tests (Device)

Tests requiring a real device or emulator are located in `src/androidTest`.

```bash
$env:ANDROID_PREFS_ROOT=$null; ./gradlew connectedDebugAndroidTest
```

### Screenshot Tests

#### Update References

```bash
$env:ANDROID_PREFS_ROOT=$null; ./gradlew updateDebugScreenshotTest
```

#### Validate

```bash
$env:ANDROID_PREFS_ROOT=$null; ./gradlew validateDebugScreenshotTest
```

### Static Analysis

```bash
$env:ANDROID_PREFS_ROOT=$null; ./gradlew detekt ktlintCheck
```

## Robolectric SDK Policy

To maintain test execution speed and minimize overhead, we follow a strict SDK consolidation policy:
- **Default SDK**: Set globally to **37** via `robolectric.properties` and Gradle system properties.
- **No Routine Pinning**: Do NOT add `@Config(sdk = [35])` or any other SDK pinning to test classes if it matches the default.
- **Behavior-Specific Tests**: Pinning a different SDK is ONLY permitted when testing logic that branches based on `Build.VERSION`. In such cases, accompany the `@Config` with a comment: `// behavior-specific SDK`.
- **Pure Logic**: Tests for classes that do not depend on Android framework (no `android.*` imports) must use the standard JUnit runner, not `RobolectricTestRunner`.

## Screenshot Testing Guidelines

- **Screen-level tests**: Test each screen in 9 different sizes (Compact, Medium, Expanded combinations).
- **Theme variations**: Mobile size (400x500) for all alternative themes.
- **Font scales**: Mobile size (400x500) with font scale 1.5.
- **Component-level tests**: Test reusable components in isolation with different themes and font scales.

## Continuous Integration

The following command should pass before any PR is merged:

```bash
$env:ANDROID_PREFS_ROOT=$null; ./gradlew ktlintCheck detekt lintDebug testDebugUnitTest :app:assembleDebug
```
