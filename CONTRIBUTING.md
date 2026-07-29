# Contributing to MinuteWatcher

Thank you for your interest in contributing to MinuteWatcher! To maintain high code quality and app stability, we follow a strict testing and architectural policy.

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
We enforce strict static analysis. Your code must pass:
- **Detekt:** No new issues should be introduced. Baseline files are for legacy issues only and should be reduced over time.
- **Lint:** The build will fail on any lint error or warning. `MissingTranslation` is a fatal error.
- **Ktlint:** Code style must follow the established Kotlin standards.

---

## Architectural Guidelines
- **Dependency Inversion:** Feature modules must never depend directly on `:core:data`. Use interfaces defined in `:core:domain`.
- **Modularity:** Keep `:core:model` free of Android dependencies to ensure high-speed testing.
- **DI:** Use Hilt for dependency injection and provide specific qualifiers (`@IoDispatcher`, etc.) instead of hardcoded dispatchers.
