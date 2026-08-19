plugins {
    `kotlin-dsl`
}

group = "com.example.minutewatcher.buildlogic"

@Suppress("UnstableApiUsage")
// VersionCatalogsExtension is @Incubating in Gradle 9.x but is the only supported
// way to share a version catalog with build-logic. This is a known Gradle limitation,
// not a heuristic choice. Suppression is intentional and documented.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    compileOnly(libs.findLibrary("android-gradlePlugin").get())
    compileOnly(libs.findLibrary("kotlin-gradlePlugin").get())
}
