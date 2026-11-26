plugins {
    id("jacoco")
}

configure<JacocoPluginExtension> {
    toolVersion = "0.8.12"
}

val libs = the<VersionCatalogsExtension>().named("libs")
val mockitoAgentConfig = project.configurations.create("mockitoAgentConfig") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
project.dependencies.add("mockitoAgentConfig", libs.findLibrary("byteBuddyAgent").get())

val robolectricAndroidAllConfigurations =
    listOf(
        "robolectricAndroidAllSdk28",
        "robolectricAndroidAllSdk29",
        "robolectricAndroidAllSdk30",
        "robolectricAndroidAllSdk31",
        "robolectricAndroidAllSdk34",
        "robolectricAndroidAllSdk35",
        "robolectricAndroidAllSdk35Alt",
        "robolectricAndroidAllSdk36",
        "robolectricAndroidAllSdk36Alt",
        "robolectricAndroidAllSdk37",
        "robolectricAndroidAllSdk37Alt",
    ).map { configurationName ->
        project.configurations.create(configurationName) {
            isCanBeConsumed = false
            isCanBeResolved = true
        }.also {
            project.dependencies.add(configurationName, libs.findLibrary(configurationName).get())
        }
    }

val prepareRobolectricJars = tasks.register<Sync>("prepareRobolectricJars") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(robolectricAndroidAllConfigurations)
    into(project.layout.buildDirectory.dir("robolectric-jars"))
    eachFile {
        path = name
    }
    includeEmptyDirs = false
}

tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricJars)
    val isVerbose = providers.gradleProperty("verboseTests").isPresent

    // Centralized Robolectric configuration
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("robolectric.offline", "true")
    inputs.files(robolectricAndroidAllConfigurations)
        .withPathSensitivity(PathSensitivity.NAME_ONLY)

    systemProperty("minutewatcher.projectRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
    systemProperty(
        "minutewatcher.coreResourcesResDir",
        rootProject.layout.projectDirectory.dir("core/resources/src/main/res").asFile.absolutePath,
    )
    systemProperty(
        "minutewatcher.appManifestPath",
        rootProject.layout.projectDirectory.dir("app/src/main/AndroidManifest.xml").asFile.absolutePath,
    )
    systemProperty(
        "minutewatcher.semanticManifestPath",
        rootProject.layout.projectDirectory.dir("feature/semantic/src/main/AndroidManifest.xml").asFile.absolutePath,
    )

    inputs.dir(rootProject.layout.projectDirectory.dir("core/resources/src/main/res"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("coreResourcesResDir")
    inputs.dir(rootProject.layout.projectDirectory.dir("fastlane/metadata/android"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("fastlaneMetadataDir")
    inputs.file(rootProject.layout.projectDirectory.file("app/src/main/AndroidManifest.xml"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("appManifestFile")
    inputs.file(rootProject.layout.projectDirectory.file("feature/semantic/src/main/AndroidManifest.xml"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("semanticManifestFile")
    inputs.file(rootProject.layout.projectDirectory.file("core/resources/src/main/assets/ATTRIBUTIONS.txt"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("attributionsFile")
    inputs.file(rootProject.layout.projectDirectory.file("core/resources/src/main/assets/OFL-1.1.txt"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("oflFile")
    inputs.dir(rootProject.layout.projectDirectory.dir("docs"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("docsDir")

    val testTmpDirFile = project.layout.buildDirectory.dir("tmp").get().asFile
    val robolectricJarsDirFile = project.layout.buildDirectory.dir("robolectric-jars").get().asFile

    doFirst {
        testTmpDirFile.mkdirs()
        systemProperty("robolectric.dependency.dir", robolectricJarsDirFile.absolutePath)
    }

    systemProperty("java.io.tmpdir", testTmpDirFile.absolutePath)
    if (isVerbose) {
        systemProperty("robolectric.logging.enabled", "true")
    }

    // Policy: Reliability over speed.
    maxHeapSize = "1536m"
    maxParallelForks = maxOf(1, minOf(Runtime.getRuntime().availableProcessors() / 2, 4))
    forkEvery = 100

    testLogging {
        showStandardStreams = isVerbose
        events(
            org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED,
        )
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }

    extensions.findByType(JacocoTaskExtension::class.java)?.apply {
        isEnabled = true
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }

    jvmArgs(
        "-Xshare:off",
        "-javaagent:${mockitoAgentConfig.singleFile.absolutePath}",
        "-Dnet.bytebuddy.experimental=true",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
    )
}
