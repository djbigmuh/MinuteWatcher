tasks.withType<Test>().configureEach {
    // Allow Mockito to self-attach on JDK 17+
    jvmArgs("-Dnet.bytebuddy.experimental=true")

    // Centralized Robolectric configuration
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty(
        "minutewatcher.coreResourcesResDir",
        rootProject.layout.projectDirectory.dir("core/resources/src/main/res").asFile.absolutePath,
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
    systemProperty("robolectric.logging.enabled", "true")

    // Policy: Reliability over speed.
    maxHeapSize = "1536m"
    maxParallelForks = 1
    forkEvery = 1

    // Disable JaCoCo agent by default to speed up test execution.
    // Enable only when -Pcoverage is passed (e.g., during a dedicated coverage run).
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
