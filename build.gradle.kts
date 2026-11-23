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

val jacocoExclusions = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/*_Factory.*",
    "**/*_Factory$*.*",
    "**/*_MembersInjector.*",
    "**/*_Provide*Factory.*",
    "**/Hilt_*",
    "**/hilt_aggregated_deps/**",
    "**/*Hilt*.*",
    "**/*AppFunction*.*",
    "**/testFixtures/**",
    "**/testing/**",
    "**/CoreDataFakes*.*",
    "**/*Test*.*",
)

subprojects {
    val modulePath = path
    val testAggregate = allUnitTests
    val detektAggregate = allDetekt
    val ktlintAggregate = allKtlint

    tasks.matching { it.name.endsWith("AarMetadata") }.configureEach {
        enabled = false
    }

    plugins.withId("com.android.application") {
        testAggregate.configure { dependsOn("$modulePath:testDebugUnitTest") }
        detektAggregate.configure { dependsOn("$modulePath:detekt") }
        ktlintAggregate.configure { dependsOn("$modulePath:ktlintCheck") }
    }
    plugins.withId("com.android.library") {
        testAggregate.configure { dependsOn("$modulePath:testDebugUnitTest") }
        detektAggregate.configure { dependsOn("$modulePath:detekt") }
        ktlintAggregate.configure { dependsOn("$modulePath:ktlintCheck") }

        val threshold = when (modulePath) {
            ":core:common" -> 0.95
            ":core:android" -> 0.90
            ":core:data" -> 0.85
            else -> null
        }

        if (threshold != null) {
            val reportTask = tasks.register<JacocoReport>("jacocoCoverageReport") {
                dependsOn("testDebugUnitTest")
                reports {
                    xml.required.set(true)
                    html.required.set(true)
                }
                val mainSrc = files("src/main/java")
                val mainClasses = fileTree("build/tmp/kotlin-classes/debug") {
                    exclude(jacocoExclusions)
                }
                sourceDirectories.setFrom(mainSrc)
                classDirectories.setFrom(mainClasses)
                executionData.setFrom(
                    files(
                        "build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                        "build/jacoco/testDebugUnitTest.exec",
                    ),
                )
            }

            tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
                dependsOn(reportTask)
                val mainClasses = fileTree("build/tmp/kotlin-classes/debug") {
                    exclude(jacocoExclusions)
                }
                classDirectories.setFrom(mainClasses)
                executionData.setFrom(
                    files(
                        "build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                        "build/jacoco/testDebugUnitTest.exec",
                    ),
                )
                violationRules {
                    rule {
                        limit {
                            minimum = threshold.toBigDecimal()
                        }
                    }
                }
            }


        }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        testAggregate.configure { dependsOn("$modulePath:test") }
        detektAggregate.configure { dependsOn("$modulePath:detekt") }
        ktlintAggregate.configure { dependsOn("$modulePath:ktlintCheck") }

        if (modulePath == ":core:model") {
            val reportTask = tasks.register<JacocoReport>("jacocoCoverageReport") {
                dependsOn("test")
                reports {
                    xml.required.set(true)
                    html.required.set(true)
                }
                val mainSrc = files("src/main/java")
                val mainClasses = fileTree("build/classes/kotlin/main") {
                    exclude(jacocoExclusions)
                }
                sourceDirectories.setFrom(mainSrc)
                classDirectories.setFrom(mainClasses)
                executionData.setFrom(fileTree("build/jacoco") { include("*.exec") })
            }

            tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
                dependsOn(reportTask)
                val mainClasses = fileTree("build/classes/kotlin/main") {
                    exclude(jacocoExclusions)
                }
                classDirectories.setFrom(mainClasses)
                executionData.setFrom(fileTree("build/jacoco") { include("*.exec") })
                violationRules {
                    rule {
                        limit {
                            minimum = 0.85.toBigDecimal()
                        }
                    }
                }
            }


        }
    }
}

tasks.named("check") {
    dependsOn(verifyVersionCatalogConsistency)
    dependsOn(allKtlint)
    dependsOn(allDetekt)
    dependsOn(allUnitTests)
}

abstract class VerifyQuietTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testResultXmls: ConfigurableFileCollection

    @get:Input
    abstract val reportDirs: ListProperty<String>

    @TaskAction
    fun verify() {
        var totalTests = 0
        var failedTests = 0
        val failedTestNames = mutableListOf<String>()

        testResultXmls.files.forEach { file ->
            if (file.exists() && file.extension == "xml") {
                try {
                    val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    val db = dbf.newDocumentBuilder()
                    val doc = db.parse(file)
                    val testsuite = doc.documentElement
                    if (testsuite != null) {
                        totalTests += testsuite.getAttribute("tests").toIntOrNull() ?: 0
                        val failures = testsuite.getAttribute("failures").toIntOrNull() ?: 0
                        val errors = testsuite.getAttribute("errors").toIntOrNull() ?: 0
                        failedTests += failures + errors

                        if (failures > 0 || errors > 0) {
                            val testcases = testsuite.getElementsByTagName("testcase")
                            for (i in 0 until testcases.length) {
                                val tc = testcases.item(i) as? org.w3c.dom.Element
                                if (tc != null) {
                                    val hasFailure = tc.getElementsByTagName("failure").length > 0 ||
                                                     tc.getElementsByTagName("error").length > 0
                                    if (hasFailure) {
                                        val className = tc.getAttribute("classname")
                                        val name = tc.getAttribute("name")
                                        failedTestNames.add("$className#$name")
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore unparseable or incomplete files
                }
            }
        }

        val success = (failedTests == 0)
        val reportsInfo = reportDirs.get().distinct().joinToString(", ")

        if (success) {
            logger.quiet("VERIFICATION SUCCESS: tests=$totalTests, failed=0, reports=[$reportsInfo]")
        } else {
            val limitedFailures = failedTestNames.take(40).joinToString("; ")
            logger.quiet("VERIFICATION FAILED: tests=$totalTests, failed=$failedTests, failedTests=[$limitedFailures], reports=[$reportsInfo]")
            throw GradleException("Verification failed: $failedTests failing tests found.")
        }
    }
}

tasks.register<VerifyQuietTask>("verifyQuiet") {
    group = "verification"
    description = "Aggregates static analysis, tests, and debug app assembly into a single compact verification task."

    dependsOn(
        allKtlint,
        allDetekt,
        allUnitTests,
        rootProject.subprojects.flatMap { sub -> sub.tasks.matching { t -> t.name == "lintDebug" || t.name == "assembleDebug" } },
    )

    testResultXmls.from(
        rootProject.subprojects.map { sub ->
            sub.layout.buildDirectory.dir("test-results").map { dir ->
                dir.asFileTree.matching { include("**/*.xml") }
            }
        },
    )

    reportDirs.set(
        rootProject.subprojects.map { sub ->
            sub.layout.buildDirectory.dir("reports").get().asFile.absolutePath
        },
    )
}
