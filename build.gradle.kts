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

abstract class VerifyVersionCatalogConsistencyTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libsTomlFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildLogicTomlFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val libsTomlFileObj = libsTomlFile.get().asFile
        val buildLogicTomlFileObj = buildLogicTomlFile.get().asFile

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

val verifyVersionCatalogConsistency =
    tasks.register<VerifyVersionCatalogConsistencyTask>("verifyVersionCatalogConsistency") {
        group = "verification"
        description =
            "Verifies that build plugin versions match between gradle/libs.versions.toml and gradle/build-logic.versions.toml"
        libsTomlFile.set(layout.projectDirectory.file("gradle/libs.versions.toml"))
        buildLogicTomlFile.set(layout.projectDirectory.file("gradle/build-logic.versions.toml"))
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

abstract class VerifyQuietTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testResultXmls: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reportDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleSources: ConfigurableFileCollection

    @get:Internal
    abstract val projectRootDir: DirectoryProperty

    @TaskAction
    fun verify() {
        val rootDir = projectRootDir.get().asFile

        // 1. Check for stale reports against module source file timestamps
        val sourceFilesList = moduleSources.files.filter { it.isFile }

        val allReportFiles = (reportDirs.files.flatMap { dir ->
            if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.toList() else if (dir.isFile) listOf(dir) else emptyList()
        } + testResultXmls.files.filter { it.isFile })
            .filter { file ->
                val path = file.invariantSeparatorsPath
                val isKtlint = path.contains("ktlint") && path.contains("Check")
                val isDetekt = path.contains("detekt")
                val isLint = path.contains("lint-results") || path.contains("lintReport")
                val isTestResult = path.contains("test-results") || path.contains("unit_test")

                val isVerificationReport = isKtlint || isDetekt || isLint || isTestResult
                isVerificationReport && (!file.name.contains("benchmark", ignoreCase = true) || file.name.contains("testBenchmark", ignoreCase = true))
            }
            .distinct()

        for (reportFile in allReportFiles) {
            var parent = reportFile.parentFile
            while (parent != null && parent.name != "build") {
                parent = parent.parentFile
            }
            val moduleDir = parent?.parentFile ?: continue

            val reportPath = reportFile.invariantSeparatorsPath
            val isKtlint = reportPath.contains("ktlint")
            val isTestResult = reportPath.contains("test-results") || reportPath.contains("unit_test")

            val relevantSources = sourceFilesList.filter { srcFile ->
                if (!srcFile.startsWith(moduleDir)) return@filter false

                val ext = srcFile.extension.lowercase()
                if (isKtlint && ext != "kt" && ext != "kts") return@filter false
                if (isTestResult && ext != "kt" && ext != "java") return@filter false

                val relativeSrcPath = srcFile.relativeTo(moduleDir).invariantSeparatorsPath
                when {
                    reportPath.contains("TestFixturesSourceSet") || reportPath.contains("testFixtures") -> relativeSrcPath.startsWith("src/testFixtures")
                    reportPath.contains("TestBenchmark") || reportPath.contains("testBenchmark") -> relativeSrcPath.startsWith("src/testBenchmark")
                    reportPath.contains("Benchmark") || reportPath.contains("benchmark") -> relativeSrcPath.startsWith("src/benchmark")
                    reportPath.contains("AndroidTest") || reportPath.contains("androidTest") -> relativeSrcPath.startsWith("src/androidTest")
                    reportPath.contains("TestSourceSet") -> relativeSrcPath.startsWith("src/test")
                    reportPath.contains("MainSourceSet") -> relativeSrcPath.startsWith("src/main")
                    reportPath.contains("KotlinScript") -> srcFile.name.endsWith(".kts")
                    else -> relativeSrcPath.startsWith("src/main") || relativeSrcPath.startsWith("src/test")
                }
            }

            if (relevantSources.isNotEmpty()) {
                val latestSourceFile = relevantSources.maxByOrNull { it.lastModified() }!!
                val sourceTime = latestSourceFile.lastModified()
                val reportTime = reportFile.lastModified()

                if (reportTime < sourceTime) {
                    val moduleName = if (moduleDir == rootDir) "root" else ":" + moduleDir.relativeTo(rootDir).invariantSeparatorsPath.replace('/', ':')
                    throw GradleException(
                        "Stale report detected in module '$moduleName': report '${reportFile.absolutePath}' (modified $reportTime) " +
                            "is older than source file '${latestSourceFile.absolutePath}' (modified $sourceTime)."
                    )
                }
            }
        }

        // 2. Parse test XML results
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
        val reportsInfo = reportDirs.files.map { it.absolutePath }.distinct().joinToString(", ")

        if (success) {
            logger.quiet("VERIFICATION SUCCESS: tests=$totalTests, failed=0, reports=[$reportsInfo]")
        } else {
            val limitedFailures = failedTestNames.take(40).joinToString("; ")
            logger.quiet("VERIFICATION FAILED: tests=$totalTests, failed=$failedTests, failedTests=[$limitedFailures], reports=[$reportsInfo]")
            throw GradleException("Verification failed: $failedTests failing tests found.")
        }
    }
}

val verifyQuiet = tasks.register<VerifyQuietTask>("verifyQuiet") {
    group = "verification"
    description = "Aggregates static analysis, tests, and debug app assembly into a single compact verification task."
    projectRootDir.set(layout.projectDirectory)
    dependsOn(
        allKtlint,
        allDetekt,
        allUnitTests,
    )
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
    "**/*_HiltModules*",
    "**/*_HiltComponents*",
    "**/hilt_aggregated_deps/**",
    "**/Dagger*Component*",
    "**/*_GeneratedInjector*",
    "**/\u0024BaseMinuteWatcherAppFunctionService_AppFunctionInventory.*",
    "**/\u0024SimUsageSummaryFactory.*",
    "**/\u0024UsageSummaryFactory.*",
    "**/\u0024Semantic_SchemaDefinitionComponentRegistry.*",
    "**/\u0024Semantic_SerializableComponentRegistry.*",
    "**/MinuteWatcherAppFunctionService.*",
)

subprojects {
    val modulePath = path
    val testAggregate = allUnitTests
    val detektAggregate = allDetekt
    val ktlintAggregate = allKtlint
    val subBuildDir = layout.buildDirectory
    val subProjectDir = layout.projectDirectory

    verifyQuiet.configure {
        reportDirs.from(subBuildDir.dir("reports"))
        testResultXmls.from(
            subBuildDir.dir("test-results").map { dir ->
                dir.asFileTree.matching { include("**/*.xml") }
            }
        )
        moduleSources.from(
            subProjectDir.dir("src").asFileTree.matching {
                exclude("**/build/**", "**/.gradle/**")
            }
        )
    }

    tasks.matching { it.name == "testBenchmarkUnitTest" }.configureEach {
        val benchmarkTest = this
        testAggregate.configure { dependsOn(benchmarkTest) }
    }

    plugins.withId("com.android.application") {
        testAggregate.configure { dependsOn("$modulePath:testDebugUnitTest") }
        detektAggregate.configure { dependsOn("$modulePath:detekt") }
        ktlintAggregate.configure { dependsOn("$modulePath:ktlintCheck") }
        verifyQuiet.configure { dependsOn("$modulePath:assembleDebug", "$modulePath:lintDebug") }
    }
    plugins.withId("com.android.library") {
        testAggregate.configure { dependsOn("$modulePath:testDebugUnitTest") }
        detektAggregate.configure { dependsOn("$modulePath:detekt") }
        ktlintAggregate.configure { dependsOn("$modulePath:ktlintCheck") }
        verifyQuiet.configure { dependsOn("$modulePath:lintDebug") }

        val threshold = when (modulePath) {
            ":core:common" -> 0.95
            ":core:android" -> 0.90
            ":core:data" -> 0.85
            else -> null
        }

        if (threshold != null) {
            val reportTask = tasks.register<JacocoReport>("jacocoCoverageReport") {
                description = "Generates JaCoCo code coverage report for library module."
                dependsOn("testDebugUnitTest")
                reports {
                    xml.required.set(true)
                    html.required.set(true)
                }
                val mainSrc = files("src/main/java")
                val mainClasses = files(
                    fileTree("build/tmp/kotlin-classes/debug") {
                        exclude(jacocoExclusions)
                    },
                    fileTree("build/intermediates/javac/debug/classes") {
                        exclude(jacocoExclusions)
                    },
                )
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
                description = "Verifies JaCoCo code coverage rules for library module."
                dependsOn(reportTask)
                val mainClasses = files(
                    fileTree("build/tmp/kotlin-classes/debug") {
                        exclude(jacocoExclusions)
                    },
                    fileTree("build/intermediates/javac/debug/classes") {
                        exclude(jacocoExclusions)
                    },
                )
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
    plugins.withId("com.android.test") {
        detektAggregate.configure { dependsOn("$modulePath:detekt") }
        ktlintAggregate.configure { dependsOn("$modulePath:ktlintCheck") }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        testAggregate.configure { dependsOn("$modulePath:test") }
        detektAggregate.configure { dependsOn("$modulePath:detekt") }
        ktlintAggregate.configure { dependsOn("$modulePath:ktlintCheck") }

        if (modulePath == ":core:model") {
            val reportTask = tasks.register<JacocoReport>("jacocoCoverageReport") {
                description = "Generates JaCoCo code coverage report for model module."
                dependsOn("test")
                reports {
                    xml.required.set(true)
                    html.required.set(true)
                }
                val mainSrc = files("src/main/java")
                val mainClasses = files(
                    fileTree("build/classes/kotlin/main") {
                        exclude(jacocoExclusions)
                    },
                    fileTree("build/classes/java/main") {
                        exclude(jacocoExclusions)
                    },
                )
                sourceDirectories.setFrom(mainSrc)
                classDirectories.setFrom(mainClasses)
                executionData.setFrom(fileTree("build/jacoco") { include("*.exec") })
            }

            tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
                description = "Verifies JaCoCo code coverage rules for model module."
                dependsOn(reportTask)
                val mainClasses = files(
                    fileTree("build/classes/kotlin/main") {
                        exclude(jacocoExclusions)
                    },
                    fileTree("build/classes/java/main") {
                        exclude(jacocoExclusions)
                    },
                )
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
