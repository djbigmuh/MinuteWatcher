# Contributing to MinuteWatcher

Thank you for your interest in contributing to MinuteWatcher! To maintain high code quality and app stability, we follow a strict testing and architectural policy.

## Development Environment Requirements
- **JDK 21** (Temurin 21.0.12+) is the mandatory version for all developers.
- The Detekt/KSP toolchain is highly sensitive to the JDK version. Higher versions (like **JDK 25**) must not be present in the system `PATH`.
- Set your JDK path locally via the `JAVA_HOME` environment variable or in your user-level
  `~/.gradle/gradle.properties` (using `org.gradle.java.home`). Do not commit this setting to the project repository.

## Testing Policy

Every contribution must be accompanied by appropriate tests. We follow the **Testing Pyramid** approach:

### 1. Unit Tests (~80%)
The majority of our tests should be unit tests. They must cover:
- **Core Logic:** Any calculators (`BillingPeriodCalculator`), date utilities, and formatting tools.
- **Data Models:** State transitions and logic within data classes.
- **Repositories:** Logic within `SimRepositoryImpl`, `SettingsRepositoryImpl`, etc. (using Mockito for dependencies).
- **ViewModels:** UI state mapping and user action handling.
- **Resource Integrity:** Consistency between language arrays in `arrays.xml`.

### 2. Integration Tests (~15%)
Used for components that interact with the Android system or multiple modules:
- **Background Work:** `InitialSetupWorker` and `UsageCheckWorker` should be tested using `WorkManager` testing tools.
- **Database Migrations:** `IdentityMigrationService` must have tests verifying data integrity across versions.
- **Trackers:** `CallTracker` and `TrafficTracker` logic.

### 3. UI Tests (~5%)
Automated UI tests should cover critical user paths:
- Onboarding flow.
- Setting minutes and data limits.
- Core widget layout rendering.

---

## Mandatory Coverage Areas

Certain modules require 100% coverage of their public API:
- `:core:model`: All utilities and calculators.
- `:core:android`: `OperatorResolver`, `PermissionManager`.
- `:feature:widget`: `WidgetUiMapper` (must test all `WidgetLayoutMode` scenarios).

## The "Bug-Fix" Rule
> [!IMPORTANT]
> Every bug fix must include a regression test that demonstrates the bug and verifies that the fix prevents it from recurring.

## Static Analysis
We enforce strict static analysis. Every developer MUST run verification before committing.
- **`.\VERIFY.bat --Quick`**: Rapid check (ktlint + detekt). Use before minor commits.
- **`.\VERIFY.bat`**: Full verification (ktlint + detekt + lint + tests + build). Use before completing any task.

Your code must pass:
- **Detekt (2.0.0-alpha.6):** No new issues should be introduced. Baseline files are for legacy issues only and should be reduced over time.
- **Lint:** The build will fail on any lint error or warning. `MissingTranslation` is a fatal error.
- **Ktlint:** Code style must follow the established Kotlin standards.

---

## Architectural Guidelines
- **Dependency Inversion:** Feature modules must never depend directly on `:core:data`. Use interfaces defined in `:core:domain`.
- **Modularity:** Keep `:core:model` free of Android dependencies to ensure high-speed testing.
- **DI:** Use Hilt for dependency injection and provide specific qualifiers (`@IoDispatcher`, etc.) instead of hardcoded dispatchers.
- **Dispatchers:** Все компоненты должны использовать инжектированные диспетчеры через Hilt-квалификаторы (`@IoDispatcher`, `@MainImmediateDispatcher`). Прямое использование `Dispatchers.IO` или `Dispatchers.Default` ЗАПРЕЩЕНО везде, кроме `DispatchersModule` и инфраструктурного класса `AppLogger`.
- **Machine-readable Errors:** При классификации ошибок в `TrackingStatus.Error` обязательно используйте `type` и `code`. Ветвление логики по тексту сообщения (`message`) СТРОГО ЗАПРЕЩЕНО, так как сообщения предназначены только для логов и пользователя.

---

### AppSearch Note
На устройствах без AppSearch (LineageOS, CalyxOS без GMS) семантический поиск автоматически отключается. Баннер ошибки не отображается для ошибок типа `NOT_SUPPORTED`.

## Non-Stable Dependencies
As a general rule, we prefer stable releases. However, some non-stable versions (Beta/Alpha) are currently used for specific reasons:
- **Baseline Profile (Beta)** and **Robolectric (Beta)** are explicitly permitted until stable versions with equivalent fixes are available.
- Before introducing a new non-stable dependency, please discuss it in an issue and document the reason in `libs.versions.toml`.

## Version Management

- **Gradle Version Catalog:** When updating `AGP` (Android Gradle Plugin) or `Kotlin` versions, you MUST update both
  `gradle/libs.versions.toml` and `gradle/build-logic.versions.toml` to keep the main project and build-logic in sync.

## Build Performance

Current build and test execution settings include several "stability workarounds" that may impact performance:

- `org.gradle.parallel=false` and `org.gradle.configuration-cache=false` in `gradle.properties`.
- `maxParallelForks = 1` and `forkEvery = 1` for unit tests to prevent memory leaks and race conditions in
  Robolectric/Hilt tests.
  These settings are intentional for the current project state and should not be changed without a comprehensive
  verification of CI stability.
