import host.serv.djbigmuh.minutewatcher.buildlogic.SdkVersions

plugins {
    id("jacoco")
}

tasks.withType<Test>().configureEach {
    // Allow Mockito to self-attach on JDK 17+
    jvmArgs("-Dnet.bytebuddy.experimental=true")

    // Centralized Robolectric configuration
    systemProperty("robolectric.sdk", SdkVersions.ROBOLECTRIC_SDK.toString())
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty(
        "minutewatcher.coreResourcesResDir",
        rootProject.layout.projectDirectory.dir("core/resources/src/main/res").asFile.absolutePath,
    )
    systemProperty(
        "minutewatcher.appResDir",
        rootProject.layout.projectDirectory.dir("app/src/main/res").asFile.absolutePath,
    )

    val roboUserHome = project.layout.buildDirectory.dir("robolectric-user-home")
    val roboCacheDir = project.layout.buildDirectory.dir("robolectric-cache")
    val testTmpDir = project.layout.buildDirectory.dir("tmp")

    doFirst {
        roboUserHome.get().asFile.mkdirs()
        roboCacheDir.get().asFile.mkdirs()
        testTmpDir.get().asFile.mkdirs()
    }

    systemProperty("robolectric.dependency.repo.dir", roboCacheDir.get().asFile.absolutePath)
    systemProperty("robolectric.dependency.repo.id", "central")
    systemProperty("robolectric.dependency.repo.url", "https://repo1.maven.org/maven2/")
    systemProperty("user.home", roboUserHome.get().asFile.absolutePath)
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
