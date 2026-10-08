# Dependency Management

This document lists the non-stable (alpha, beta, RC) dependencies used in the project, the reasons for their inclusion, and the plan for migrating to stable versions.

## Non-stable Dependencies

### 1. Robolectric (Beta)
- **Version**: `4.17-beta-2`
- **Reason**: Required for Android SDK 37 support in unit tests. Standard stable versions do not yet fully support the latest platform APIs.
- **Plan**: Upgrade to the stable `4.17.x` release when available.

### 2. AndroidX Baseline Profile (RC)
- **Version**: `1.5.0-rc01`
- **Reason**: Contains critical fixes for baseline profile generation and is required for compatibility with Gradle 9.x.
- **Plan**: Transition to the stable `1.5.0` version once released and verified.

### 3. Android Gradle Plugin (Alpha/Beta)
- **Version**: `9.4.1`
- **Reason**: The project requires the latest build system features to support SDK 37 and experimental Gradle 9.x integrations.
- **Plan**: Continue using the latest preview versions until a stable branch for the 9.x series is established.

### 4. Kotlin (Future/Preview)
- **Version**: `2.4.10`
- **Reason**: Required for compatibility with the advanced AGP and Compose features targeted by this project.
- **Plan**: Monitor Kotlin release cycles and align with stable 2.4.x releases once they reach general availability.

### 5. Detekt (Alpha)
- **Version**: `2.0.0-alpha.6`
- **Reason**: Required for compatibility with Gradle 9.x and modern JDKs until the stable 2.x branch is released.
- **Plan**: Upgrade to the stable 2.x release once available; revise `config/detekt/detekt.yml` for new and deprecated
  rules; verify `buildUponDefaultConfig` behavior.

### 6. AndroidX AppSearch Platform Storage

- **Artifact**: `androidx.appsearch:appsearch-platform-storage` (1.1.0)
- **Purpose**: Enables system search integration on Android 12+ (API 31+) via `PlatformStorage`.

---

> [!NOTE]
> All non-stable dependencies are carefully selected to meet project requirements (targetSdk 37, Gradle 9.x). Downgrading is prohibited.
