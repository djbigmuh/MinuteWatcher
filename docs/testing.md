# Testing Strategy

This document describes the testing strategy, benchmark measurements, and execution policies for the MinuteWatcher project.

## Testing Stack

- **Unit Testing**: JUnit 4
- **Dependency Injection**: Hilt (with Hilt Testing for integration/instrumented tests)
- **Mocking**: Mockito with Mockito-Kotlin
- **UI Testing**:
    - **Jetpack Compose**: Compose Testing APIs (run locally with Robolectric in `src/test` or on device in `src/androidTest`)
- **E2E/Macrobenchmark**: UI Automator + Macrobenchmark (in `:macrobenchmark`)
- **Static Analysis**: Detekt + Ktlint
- **Code Coverage**: JaCoCo (run separately on-demand)

---

## Benchmark Measurements & Reference Values

Measurements taken on a warm daemon without clean or forced task re-runs (`--rerun-tasks`):

| Operation / Module | Duration (Warm Daemon) | Output Line Count |
| :--- | :--- | :--- |
| **Full Verification Run** (`verifyQuiet`) | ~2.0 s | 1 line (`VERIFICATION SUCCESS: tests=1775, failed=0, reports=[...]`) |
| **Second Run (Up-to-Date / Cached)** | ~1.8 s | 1 line |
| **Pure-JVM Module Tests** (`:core:model:test`) | ~3.0 s | Compact summary or success indicator |
| **Robolectric Module Tests** (`:core:resources:testDebugUnitTest`) | ~2.9 s | Compact summary or success indicator |
| **Test JVM Starts per Full Run** | Minimized via JVM reuse | N/A |

---

## Verification Composition & Policies

### 1. Routine Verification vs. On-Demand Checks
- **Routine Verification (`verifyQuiet`)**:
  Includes static analysis (`ktlintCheck`, `detekt`, `lintDebug`), unit tests across all modules (`testDebugUnitTest`), and debug app assembly (`:app:assembleDebug`).
  *Note*: Code coverage measurement tasks (`jacocoCoverageReport`, `jacocoCoverageVerification`) and coverage agents are **excluded** from the routine verification graph to maintain high execution speed and avoid runtime overhead.
- **On-Demand Checks (Coverage)**:
  To measure and verify code coverage separately, run:
  ```powershell
  $env:ANDROID_PREFS_ROOT=$null; ./gradlew jacocoCoverageReport jacocoCoverageVerification
  ```

### 2. Fork Policy & Reliability
- **Parameters**: `forkEvery = 100`, `maxParallelForks` bounded proportionally by available CPU cores (`Runtime.getRuntime().availableProcessors() / 2`, capped between 1 and 4).
- **Reliability Confirmation**: Verified across multiple consecutive full runs with identical result sets and stable execution ordering.
- **Condition for Modification**: Fork policies or test JVM isolation settings must only be changed if backed by confirmed performance measurements and thorough isolation regression checks.

### 3. Execution Protocol & Diagnostics
- **Single Verification Command**:
  ```powershell
  $env:ANDROID_PREFS_ROOT=$null; ./gradlew -q verifyQuiet
  ```
- **Diagnostics & Full Reports**:
  - Full HTML and XML test reports are persistently stored under `build/reports/tests/...` and `build/test-results/...`.
  - For verbose output during troubleshooting, enable verbose mode via `-PverboseTests`:
    ```powershell
    $env:ANDROID_PREFS_ROOT=$null; ./gradlew allUnitTests -PverboseTests
    ```
  - Clean (`clean`) and forced re-runs (`--rerun-tasks`) should be used strictly when resolving stale cache issues.
