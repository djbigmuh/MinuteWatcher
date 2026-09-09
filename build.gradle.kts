import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

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
    apply(plugin = "dev.detekt")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension>("detekt") {
        // Lint baseline captures ONLY pre-existing issues.
        // New code MUST NOT be added to the baseline — fix issues at the source.
        // When refactoring a file, remove its entries from the baseline first.
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        baseline.set(file("$projectDir/detekt-baseline.xml"))
        buildUponDefaultConfig.set(true)
        allRules.set(false)
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<KtlintExtension> {
        android.set(true)
        ignoreFailures.set(false)
        reporters {
            reporter(ReporterType.PLAIN)
            reporter(ReporterType.CHECKSTYLE)
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
        // При 48Гб ОЗУ и 12 ядрах увеличена квота памяти и форков для ускорения.
        // Phase 1: Parallel execution across modules.
        // Phase 2: Structural acceleration via sandbox reuse (forkEvery > 1).
        // Isolation is maintained by SingletonResetRule and AppLoggerResetRule.
        maxHeapSize = "1536m"
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        forkEvery = 1 // Default. Optimized in subprojects.

        // Disable JaCoCo agent by default to speed up test execution.
        // Enable only when -Pcoverage is passed (e.g., in CI coverage jobs).
        extensions.findByType(JacocoTaskExtension::class.java)?.isEnabled =
            providers.gradleProperty("coverage").isPresent

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
