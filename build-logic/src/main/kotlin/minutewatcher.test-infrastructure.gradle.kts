apply(plugin = "jacoco")

configure<JacocoPluginExtension> {
    toolVersion = "0.8.12"
}

tasks.withType<Test>().configureEach {
    val isVerbose = providers.gradleProperty("verboseTests").isPresent

    // Centralized Robolectric configuration
    systemProperty("robolectric.graphicsMode", "NATIVE")
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

    val moduleCacheName = project.path.removePrefix(":").replace(':', '.').ifEmpty { "root" }
    val roboUserHomeFile = rootProject.layout.projectDirectory.dir(".robolectric/$moduleCacheName/home").asFile
    val testTmpDirFile = project.layout.buildDirectory.dir("tmp").get().asFile

    doFirst {
        roboUserHomeFile.mkdirs()
        testTmpDirFile.mkdirs()
    }

    systemProperty("robolectric.dependency.repo.id", "central")
    systemProperty("robolectric.dependency.repo.url", "https://repo1.maven.org/maven2/")
    systemProperty("user.home", roboUserHomeFile.absolutePath)
    systemProperty("java.io.tmpdir", testTmpDirFile.absolutePath)
    if (isVerbose) {
        systemProperty("robolectric.logging.enabled", "true")
    }

    // Policy: Reliability over speed.
    maxHeapSize = "1536m"
    maxParallelForks = 1
    forkEvery = 1

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
        isEnabled = providers.gradleProperty("coverage").isPresent
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }

    jvmArgs(
        "-Xshare:off",
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
