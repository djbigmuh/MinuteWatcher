# Dependency Management

This document lists the non-stable (alpha, beta, RC) dependencies used in the project, the reasons for their inclusion, and the plan for migrating to stable versions.

## Non-stable Dependencies

### 1. Android Gradle Plugin

- **Version**: `9.5.1`
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

## Disabled Android Lint Checks

The following Android Lint checks are globally disabled in `minutewatcher.android.lint.gradle.kts`:

1. **`GradleDependency`**
   - **Criteria**: Dependency versions are managed centrally across the project via version catalogs (`libs.versions.toml`).
   - **Replacement Mechanism**: Centralized version catalog inspection and automated dependency update policies.

2. **`AndroidGradlePluginVersion`**
   - **Criteria**: AGP version is managed centrally via version catalogs and convention plugins.
   - **Replacement Mechanism**: Build-logic convention configuration enforcing uniform AGP versioning across all modules.

3. **`VectorPath`**
   - **Criteria**: Complex vector icon paths used for operator brand icons.
   - **Replacement Mechanism**: Validated via automated compliance tests (`OperatorIconAssetComplianceTest` in `:core:resources`).

4. **`ObsoleteSdkInt`**
    - **Criteria**: False positive on `mipmap-anydpi-v26` resource directory naming required for adaptive icons (minSdk
      26).
    - **Replacement Mechanism**: Ignored globally via `lint.ignore` in `minutewatcher.android.lint.gradle.kts`; adaptive
      icon resources are validated by AGP resource merging during release builds.

---

> [!NOTE]
> All non-stable dependencies are carefully selected to meet project requirements (targetSdk 37, Gradle 9.x). Downgrading is prohibited.

## Module Dependency Notes

- `implementation(project(":core:resources"))` edges are required in every module that imports
  `host.serv.djbigmuh.minutewatcher.core.resources.R` for shared drawables and strings. With
  `android.nonTransitiveRClass=true`, R classes are not re-exported transitively, so each
  consumer must declare the dependency directly.
- Test-only edges (`testImplementation(project(...))`, `testFixtures(project(...))`) are required
  to compile shared fixtures and cross-module unit tests.
- The IDE inspection "Unnecessary module dependency" does not resolve generated R classes and
  reports false positives on the edges above; it is disabled in the committed project inspection
  profile (`.idea/inspectionProfiles/Project_Default.xml`).

