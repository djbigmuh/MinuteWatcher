import host.serv.djbigmuh.minutewatcher.buildlogic.SdkVersions

plugins {
    `java-library`
    kotlin("jvm")
    id("minutewatcher.static-analysis")
    id("minutewatcher.test-infrastructure")
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
    jvmToolchain(SdkVersions.JAVA_TOOLCHAIN_VERSION)
}
