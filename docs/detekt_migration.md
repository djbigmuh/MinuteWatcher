# Detekt 2.x Migration Status

This document tracks the migration of the MinuteWatcher project from Detekt 1.x to Detekt 2.x.

## Status

As of 2026-09-12, the initial phase of migration is complete. The project has moved to `detekt = 2.0.0-alpha.6`, which
natively supports modern JDK versions.

## Completed Tasks

- [x] **Update Version:** Updated `detekt` to `2.0.0-alpha.6` in `gradle/libs.versions.toml`.
- [x] **Remove Workaround:** The `jdkHome = JDK21` workaround in the root `build.gradle.kts` has been removed as it is
  no longer necessary.

## Remaining Tasks

- [ ] **Finalize Stable 2.0:** Update to the stable release of Detekt 2.0 once available.
- [ ] **Full Config Revision:**
    - Review `config/detekt/detekt.yml` for new rules or deprecated properties in 2.x.
    - Validate `buildUponDefaultConfig` behavior.
- [ ] **Cleanup Deprecations:**
    - Remove old `@Suppress("UnstableApiUsage")` annotations related to detekt properties if they are now stable.

## Verification

Instead of CI (GitHub Actions), use the local verification script:

- Run `.\VERIFY.bat` and ensure `detekt` task passes.
  (Note: `detektMain` contains experimental rules and may be run manually for deep analysis).

## Historical Context (The JDK Workaround)

Prior to the 2.x alpha, a workaround was required to force Detekt to use JDK 21 due to internal Kotlin compiler
incompatibilities with newer Java version strings. This block has been removed:

```kotlin
// REMOVED (Historical)
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    project.extensions.findByType<JavaToolchainService>()?.let { javaToolchains ->
        jdkHome.set(...)
    }
}
```
