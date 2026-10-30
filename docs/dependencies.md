# Dependency Management

This document lists the non-stable (alpha, beta, RC) dependencies used in the project, the reasons for their inclusion, and the plan for migrating to stable versions.

## Non-stable Dependencies

### 1. Android Gradle Plugin

- **Version**: `9.5.0`
- **Reason**: The project requires the latest build system features to support SDK 37 and experimental Gradle 9.x integrations.
- **Plan**: Continue using the latest preview versions until a stable branch for the 9.x series is established.

### 2. Detekt (Alpha)
- **Version**: `2.0.0-alpha.6`
- **Reason**: Required for compatibility with Gradle 9.x and modern JDKs until the stable 2.x branch is released.
- **Plan**: Upgrade to the stable 2.x release once available; revise `config/detekt/detekt.yml` for new and deprecated
  rules; verify `buildUponDefaultConfig` behavior.

### 3. AndroidX AppSearch Platform Storage

- **Artifact**: `androidx.appsearch:appsearch-platform-storage` (1.1.0)
- **Purpose**: Enables system search integration on Android 12+ (API 31+) via `PlatformStorage`.

### 4. AndroidX AppFunctions (Beta)

- **Version**: `1.0.0-beta01`
- **Reason**: On-device assistant integration requires compile-time API `@AppFunctionServiceEntryPoint` and
  service/schema generation via KSP; stable release is not available yet.
- **Plan**: Transition to the stable release once available; verify manifest contract, generated service name, and
  generated asset contents upon migration.

---

> [!NOTE]
> All non-stable dependencies are carefully selected to meet project requirements (targetSdk 37, Gradle 9.x). Downgrading is prohibited.
