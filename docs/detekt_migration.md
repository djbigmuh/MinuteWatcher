# Detekt 2.x Migration Guide

This document outlines the steps required to migrate the MinuteWatcher project from Detekt 1.23.8 to Detekt 2.x once it reaches a stable release.

## Background

Detekt 1.23.8 uses an internal Kotlin 1.9 compiler, which is incompatible with Java version strings from JDK 25 and above. This necessitated a workaround in `build.gradle.kts` to force the use of JDK 21 for all Detekt tasks.

## Migration Checklist

- [ ] **Update Version:** Update the `detekt` version in `gradle/libs.versions.toml` to the latest stable 2.x version.
- [ ] **Remove Workaround:** In the root `build.gradle.kts`, remove the `tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach` block that forces `jdkHome` to JDK 21.
- [ ] **Update Configuration:**
    - Review `config/detekt/detekt.yml` for any breaking changes in rule names or properties.
    - Check if `buildUponDefaultConfig` behavior has changed.
- [ ] **Cleanup Deprecations:**
    - Remove the `@Suppress("UnstableApiUsage")` and specific comments related to `ReportingExtension.file` if they were addressed in the plugin itself.
- [ ] **Validate Reports:** Ensure that Detekt reports are still correctly generated in `build/reports/detekt/`.
- [ ] **Verify CI:** Ensure the GitHub Actions workflow still passes static analysis.

## Current Workaround

```kotlin
// build.gradle.kts
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    // ...
    project.extensions.findByType<JavaToolchainService>()?.let { javaToolchains ->
        jdkHome.set(
            javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            }.map { it.metadata.installationPath }
        )
    }
}
```
*Note: This block must be removed after migration.*
