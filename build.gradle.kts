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
            val keysToCheck = listOf("agp", "kotlin", "ktlint", "detekt")

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

            val mainVersions = parseVersions(libsTomlFile.asFile)
            val buildLogicVersions = parseVersions(buildLogicTomlFile.asFile)

            val mismatches = mutableListOf<String>()
            for (key in keysToCheck) {
                val mainVal = mainVersions[key]
                val buildLogicVal = buildLogicVersions[key]
                if (mainVal == null) {
                    mismatches.add("Key '$key' is missing in gradle/libs.versions.toml")
                }
                if (buildLogicVal == null) {
                    mismatches.add("Key '$key' is missing in gradle/build-logic.versions.toml")
                }
                if (mainVal != null && buildLogicVal != null && mainVal != buildLogicVal) {
                    mismatches.add(
                        "Version mismatch for key '$key': gradle/libs.versions.toml has '$mainVal', " +
                            "gradle/build-logic.versions.toml has '$buildLogicVal'",
                    )
                }
            }

            if (mismatches.isNotEmpty()) {
                throw GradleException("Build plugin version catalog mismatch found:\n" + mismatches.joinToString("\n"))
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyVersionCatalogConsistency)
}
