import org.gradle.api.reporting.ReportingExtension

// DO NOT DOWNGRADE SDK VERSIONS. minSdk 26, targetSdk 37, compileSdk 37 are project requirements.
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidTest) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.hiltAndroid) apply false
    alias(libs.plugins.kotlinKsp) apply false
    alias(libs.plugins.androidxBaselineprofile) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    // NOTE: Remaining deprecation warnings in this block (ReportingExtension.file)
    // originate from the Detekt 1.23.8 plugin itself and cannot be fixed without
    // upgrading to Detekt 2.x (currently in alpha).
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        // Lint baseline captures ONLY pre-existing issues.
        // New code MUST NOT be added to the baseline — fix issues at the source.
        // When refactoring a file, remove its entries from the baseline first.
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        baseline = file("$projectDir/detekt-baseline.xml")
        buildUponDefaultConfig = true
        allRules = false
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        android.set(true)
        ignoreFailures.set(false)
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        }
    }

    // NOTE: Detekt 1.23.8 + Gradle 9.7 + Kotlin 2.4 requires explicit jvmToolchain
    // and reportsDir override. If this breaks after a detekt upgrade, revisit
    // whether the workaround is still needed.
    // Detekt 1.x uses an internal Kotlin compiler that fails to parse Java version strings >= 25.
    // We force the task to use JDK 21 and override the reports directory to avoid Gradle 10 warnings.
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        val reporting = project.extensions.getByType<ReportingExtension>()
        reportsDir.set(
            reporting.baseDirectory.dir("detekt").map { it.asFile },
        )

        project.extensions.findByType<JavaToolchainService>()?.let { javaToolchains ->
            jdkHome.set(
                javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(21))
                }.map { it.metadata.installationPath }
            )
        }
    }
}

subprojects {
    tasks.withType<Test>().configureEach {
        val isAppModule = project.path == ":app"
        failOnNoDiscoveredTests = !isAppModule
        filter {
            isFailOnNoMatchingTests = !isAppModule
        }
        // Allow Mockito to self-attach on JDK 17+
        jvmArgs("-Dnet.bytebuddy.experimental=true")
        // Robolectric + JDK 17/21 compatibility
        jvmArgs(
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        )
    }
}
