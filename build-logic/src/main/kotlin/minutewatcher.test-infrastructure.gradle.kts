plugins {
    id("jacoco")
}

tasks.withType<Test>().configureEach {
    // Allow Mockito to self-attach on JDK 17+
    jvmArgs("-Dnet.bytebuddy.experimental=true")

    // Centralized Robolectric configuration
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty(
        "minutewatcher.coreResourcesResDir",
        rootProject.layout.projectDirectory.dir("core/resources/src/main/res").asFile.absolutePath,
    )
    systemProperty(
        "minutewatcher.appResDir",
        rootProject.layout.projectDirectory.dir("app/src/main/res").asFile.absolutePath,
    )

    val moduleCacheName = project.path.removePrefix(":").replace(':', '.').ifEmpty { "root" }
    val roboUserHome = rootProject.layout.projectDirectory.dir(".robolectric/$moduleCacheName/home")
    val testTmpDir = project.layout.buildDirectory.dir("tmp")

    doFirst {
        roboUserHome.asFile.mkdirs()
        testTmpDir.get().asFile.mkdirs()
    }

    systemProperty("robolectric.dependency.repo.id", "central")
    systemProperty("robolectric.dependency.repo.url", "https://repo1.maven.org/maven2/")
    systemProperty("user.home", roboUserHome.asFile.absolutePath)
    systemProperty("java.io.tmpdir", testTmpDir.get().asFile.absolutePath)
    systemProperty("robolectric.logging.enabled", "true")

    // Policy: Reliability over speed.
    maxHeapSize = "1536m"
    maxParallelForks = 1
    forkEvery = 1

    // Disable JaCoCo agent by default to speed up test execution.
    // Enable only when -Pcoverage is passed (e.g., in CI coverage jobs).
    extensions.findByType(JacocoTaskExtension::class.java)?.isEnabled =
        providers.gradleProperty("coverage").isPresent

    jvmArgs(
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
