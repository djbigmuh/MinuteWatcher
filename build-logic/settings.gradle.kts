// Suppress UnstableApiUsage for org.gradle.api.initialization.dsl.DependencyResolutionManagement and VersionCatalogsExtension @Incubating in Gradle 9.x
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    @Suppress("UnstableApiUsage")
    versionCatalogs {
        create("libs") {
            from(files("../gradle/build-logic.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
