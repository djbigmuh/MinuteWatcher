import dev.detekt.gradle.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    id("dev.detekt")
    id("org.jlleitschuh.gradle.ktlint")
    id("jacoco")
}

extensions.configure<DetektExtension>("detekt") {
    config.setFrom(files("${rootDir}/config/detekt/detekt.yml"))
    buildUponDefaultConfig.set(true)
    allRules.set(true)
}

configure<KtlintExtension> {
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE)
    }
}

tasks.withType<JacocoReport>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
