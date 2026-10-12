plugins {
    `kotlin-dsl`
}

group = "com.example.minutewatcher.buildlogic"

// versionCatalogs is the modern accessor (Gradle 7.4+).
// VersionCatalogsExtension is deprecated and will be removed in Gradle 10.
val libs = versionCatalogs.named("libs")

dependencies {
    compileOnly(libs.findLibrary("android-gradlePlugin").get())
    compileOnly(libs.findLibrary("kotlin-gradlePlugin").get())
    compileOnly(libs.findLibrary("ktlint-gradlePlugin").get())
    compileOnly(libs.findLibrary("detekt-gradlePlugin").get())
}
