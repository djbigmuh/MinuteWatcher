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

    // NOTE: Detekt 1.23.8 + Gradle 9.7 + Kotlin 2.4 requires explicit jvmToolchain and reportsDir override.
    // This workaround must be revisited when upgrading Detekt to 2.x.
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        val reporting = project.extensions.getByType<ReportingExtension>()
        reportsDir.set(
            reporting.baseDirectory.dir("detekt").map { it.asFile },
        )

        // Detekt 1.23.8 embeds Kotlin 1.9, which cannot parse Java version strings >= 25.
        // Force JDK 21 toolchain regardless of the host JAVA_HOME to avoid
        // java.lang.IllegalArgumentException during JVM version parsing.
        // This is a strict API compatibility requirement, not a heuristic.
        project.extensions.findByType<JavaToolchainService>()?.let { toolchains ->
            jdkHome.set(
                toolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(21))
                }.map { it.metadata.installationPath }
            )
        }
    }

    apply(plugin = "jacoco")

    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}

subprojects {
    tasks.withType<Test>().configureEach {
        // Allow Mockito to self-attach on JDK 17+
        jvmArgs("-Dnet.bytebuddy.experimental=true")
        // Centralized Robolectric configuration
        systemProperty("robolectric.sdk", "37")
        // Robolectric и Mockito требуют --add-opens для JDK 17+ из-за использования internal API.
        // Это строгое соответствие требованиям инструментов тестирования, не эвристика.
        maxHeapSize = "3072m"
        maxParallelForks = 1
        forkEvery = 1

        jvmArgs(
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens=java.base/java.text=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        )
    }
}
