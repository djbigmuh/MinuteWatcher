pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
// dependencyResolutionManagement is @Incubating in Gradle 9.x but is the supported way to
// centralize repository configuration for all modules. Suppression is intentional and documented.
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MinuteWatcher"
include(":app")
include(":core:model")
include(":core:common")
include(":core:android")
include(":core:resources")
include(":core:domain")
include(":core:data")
include(":feature:main")
include(":feature:widget")
include(":feature:settings")
include(":feature:semantic")
include(":macrobenchmark")
include(":baselineprofile")
