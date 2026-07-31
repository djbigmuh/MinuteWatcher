// DO NOT DOWNGRADE SDK VERSIONS. minSdk 26, targetSdk 37, compileSdk 37 are project requirements.
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidTest) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.hiltAndroid) apply false
    alias(libs.plugins.kotlinKsp) apply false
    alias(libs.plugins.androidxBaselineprofile) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt)
}

allprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
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

    // Workaround for Gradle 10 deprecation in detekt 1.x
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        reportsDir.set(
            project.layout.buildDirectory
                .dir("reports/detekt")
                .map { it.asFile },
        )
    }
}

subprojects {
    tasks.withType<Test>().configureEach {
        failOnNoDiscoveredTests = false
        filter {
            isFailOnNoMatchingTests = false
        }
        // Allow Mockito to self-attach on JDK 17+
        jvmArgs("-Dnet.bytebuddy.experimental=true")
        // Default SDK for Robolectric
        jvmArgs("-Drobolectric.sdk=37")
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
