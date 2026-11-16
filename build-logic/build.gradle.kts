import dev.detekt.gradle.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    `kotlin-dsl`
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

group = "host.serv.djbigmuh.minutewatcher.buildlogic"

// versionCatalogs is the modern accessor (Gradle 7.4+).
// VersionCatalogsExtension is deprecated and will be removed in Gradle 10.
val libs = versionCatalogs.named("libs")

dependencies {
    compileOnly(libs.findLibrary("android-gradlePlugin").get())
    compileOnly(libs.findLibrary("kotlin-gradlePlugin").get())
    compileOnly(libs.findLibrary("ktlint-gradlePlugin").get())
    compileOnly(libs.findLibrary("detekt-gradlePlugin").get())
}

extensions.configure<DetektExtension>("detekt") {
    config.setFrom(files("${rootDir}/../config/detekt/detekt.yml"))
    buildUponDefaultConfig.set(true)
    allRules.set(true)
}

configure<KtlintExtension> {
    ignoreFailures.set(false)
}
