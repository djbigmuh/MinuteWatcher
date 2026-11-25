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

val robolectricDir = settings.rootDir.resolve(".robolectric")
val repoDir = robolectricDir.resolve("repo")
repoDir.mkdirs()
if (robolectricDir.exists()) {
    robolectricDir.walkTopDown().filter { it.isFile && (it.extension == "jar" || it.extension == "pom") && it.path.contains("android-all-instrumented") }.forEach { file ->
        val idx = file.path.indexOf("org/robolectric/android-all-instrumented")
        if (idx != -1) {
            val relPath = file.path.substring(idx)
            val targetFile = repoDir.resolve(relPath)
            targetFile.parentFile.mkdirs()
            file.copyTo(targetFile, overwrite = true)
        }
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = settings.rootDir.resolve(".robolectric/repo").toURI() }
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
