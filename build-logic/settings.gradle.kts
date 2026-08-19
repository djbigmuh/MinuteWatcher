@file:Suppress("UnstableApiUsage")
// VersionCatalogsExtension is @Incubating in Gradle 9.x but is the only supported
// way to share a version catalog with build-logic. This is a known Gradle limitation,
// not a heuristic choice. Suppression is intentional and documented.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
