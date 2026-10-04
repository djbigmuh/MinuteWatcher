import dev.detekt.gradle.extensions.DetektExtension
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

    extensions.configure<DetektExtension>("detekt") {
        // Detekt baseline contains ONLY pre-existing issues.
        // New issues MUST NOT be added to the baseline — fix them at the source.
        // Regenerating or expanding the baseline during routine edits is forbidden;
        // increasing the entry count is grounds for rejecting a change.
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

    tasks.withType<Test>().configureEach {
        // Allow Mockito to self-attach on JDK 17+
        jvmArgs("-Dnet.bytebuddy.experimental=true")
        // Centralized Robolectric configuration
        systemProperty("robolectric.sdk", "37")
        // Redirect Robolectric dependencies and home to avoid paths containing spaces or non-ASCII characters.
        // This fixes java.nio.file.NoSuchFileException caused by URI encoding and native runtime loading issues.
        val roboUserHome = rootProject.layout.projectDirectory.dir(".gradle/user-home")
        val roboCacheDir = rootProject.layout.projectDirectory.dir(".gradle/robolectric-cache")
        val testTmpDir = project.layout.buildDirectory.dir("tmp")

        doFirst {
            roboUserHome.asFile.mkdirs()
            roboCacheDir.asFile.mkdirs()
            testTmpDir.get().asFile.mkdirs()
        }

        systemProperty("robolectric.dependency.repo.dir", roboCacheDir.asFile.absolutePath)
        systemProperty("robolectric.dependency.repo.id", "central")
        systemProperty("robolectric.dependency.repo.url", "https://repo1.maven.org/maven2/")
        systemProperty("user.home", roboUserHome.asFile.absolutePath)
        systemProperty("java.io.tmpdir", testTmpDir.get().asFile.absolutePath)
        systemProperty("robolectric.logging.enabled", "true")

        // Policy: Reliability over speed.
        // maxParallelForks = 1 and forkEvery = 1 prevent Robolectric native runtime issues on Windows.
        maxHeapSize = "1536m"
        maxParallelForks = 1
        forkEvery = 1

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
