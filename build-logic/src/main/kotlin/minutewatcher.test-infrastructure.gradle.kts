plugins {
    id("jacoco")
}

tasks.withType<Test>().configureEach {
    // Allow Mockito to self-attach on JDK 17+
    jvmArgs("-Dnet.bytebuddy.experimental=true")

    // Centralized Robolectric configuration
    systemProperty("robolectric.sdk", "37")
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty(
        "minutewatcher.coreResourcesResDir",
        File(rootDir, "core/resources/src/main/res").absolutePath,
    )
    systemProperty(
        "minutewatcher.appResDir",
        File(rootDir, "app/src/main/res").absolutePath,
    )

    val roboUserHome = rootProject.layout.projectDirectory.dir(".gradle/user-home")
    val roboCacheDir = rootProject.layout.projectDirectory.dir(".gradle/robolectric-cache")
    val testTmpDir = project.layout.buildDirectory.dir("tmp")

    doFirst {
        roboUserHome.asFile.mkdirs()
        roboCacheDir.asFile.mkdirs()
        testTmpDir.get().asFile.mkdirs()
    }

    systemProperty("robolectric.dependency.repo.dir", roboCacheDir.asFile.absolutePath)
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
