plugins {
    base
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

val verifyVersionCatalogConsistency =
    tasks.register("verifyVersionCatalogConsistency") {
        group = "verification"
        description =
            "Verifies that build plugin versions match between gradle/libs.versions.toml and gradle/build-logic.versions.toml"

        val libsTomlFile = layout.projectDirectory.file("gradle/libs.versions.toml")
        val buildLogicTomlFile = layout.projectDirectory.file("gradle/build-logic.versions.toml")

        inputs.file(libsTomlFile)
        inputs.file(buildLogicTomlFile)

        doLast {
            val inputFiles = inputs.files.files
            val libsTomlFileObj = inputFiles.firstOrNull { it.name == "libs.versions.toml" }
                ?: error("libs.versions.toml not found in task inputs")
            val buildLogicTomlFileObj = inputFiles.firstOrNull { it.name == "build-logic.versions.toml" }
                ?: error("build-logic.versions.toml not found in task inputs")

            fun parseVersions(file: File): Map<String, String> {
                val versions = mutableMapOf<String, String>()
                var inVersionsSection = false
                file.forEachLine { rawLine ->
                    val trimmed = rawLine.trim()
                    if (trimmed.startsWith("#")) {
                        return@forEachLine
                    }
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        inVersionsSection = trimmed == "[versions]"
                    } else if (inVersionsSection && trimmed.contains("=")) {
                        val eqIndex = trimmed.indexOf('=')
                        if (eqIndex != -1) {
                            val key = trimmed.substring(0, eqIndex).trim().removeSurrounding("\"").removeSurrounding("'")
                            var valuePart = trimmed.substring(eqIndex + 1).trim()
                            var inQuotes = false
                            var commentIndex = -1
                            for (i in 0 until valuePart.length) {
                                val c = valuePart[i]
                                if (c == '"' || c == '\'') {
                                    inQuotes = !inQuotes
                                } else if (c == '#' && !inQuotes) {
                                    commentIndex = i
                                    break
                                }
                            }
                            if (commentIndex != -1) {
                                valuePart = valuePart.substring(0, commentIndex).trim()
                            }
                            val value = valuePart.removeSurrounding("\"").removeSurrounding("'")
                            if (key.isNotEmpty()) {
                                versions[key] = value
                            }
                        }
                    }
                }
                return versions
            }

            val mainVersions = parseVersions(libsTomlFileObj)
            val buildLogicVersions = parseVersions(buildLogicTomlFileObj)

            val mismatches = mutableListOf<String>()
            for ((key, buildLogicVal) in buildLogicVersions) {
                val mainVal = mainVersions[key]
                if (mainVal == null) {
                    mismatches.add("Key '$key' defined in gradle/build-logic.versions.toml is missing in gradle/libs.versions.toml")
                } else if (mainVal != buildLogicVal) {
                    mismatches.add(
                        "Version mismatch for '$key': gradle/libs.versions.toml has '$mainVal', " +
                            "gradle/build-logic.versions.toml has '$buildLogicVal'",
                    )
                }
            }

            if (mismatches.isNotEmpty()) {
                throw GradleException("Version catalog consistency check failed:\n" + mismatches.joinToString("\n"))
            }
        }
    }

val allUnitTests =
    tasks.register("allUnitTests") {
        group = "verification"
        description = "Runs unit tests of every module: testDebugUnitTest for Android modules, test for pure-JVM modules."
    }

val allDetekt =
    tasks.register("allDetekt") {
        group = "verification"
        description = "Runs detekt analysis across all subprojects."
    }

val allKtlint =
    tasks.register("allKtlint") {
        group = "verification"
        description = "Runs ktlintCheck across all subprojects."
    }

subprojects {
    val modulePath = path
    val testAggregate = allUnitTests
    val detektAggregate = allDetekt
    val ktlintAggregate = allKtlint

    plugins.withId("com.android.application") {
        testAggregate.configure { dependsOn("$modulePath:testDebugUnitTest") }
        detektAggregate.configure { dependsOn("$modulePath:detekt") }
        ktlintAggregate.configure { dependsOn("$modulePath:ktlintCheck") }
    }
    plugins.withId("com.android.library") {
        testAggregate.configure { dependsOn("$modulePath:testDebugUnitTest") }
        detektAggregate.configure { dependsOn("$modulePath:detekt") }
        ktlintAggregate.configure { dependsOn("$modulePath:ktlintCheck") }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        testAggregate.configure { dependsOn("$modulePath:test") }
        detektAggregate.configure { dependsOn("$modulePath:detekt") }
        ktlintAggregate.configure { dependsOn("$modulePath:ktlintCheck") }
    }
}

tasks.named("check") {
    dependsOn(verifyVersionCatalogConsistency)
    dependsOn(allKtlint)
    dependsOn(allDetekt)
    dependsOn(allUnitTests)
}
