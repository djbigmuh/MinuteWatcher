import com.android.build.api.dsl.ApplicationExtension
import host.serv.djbigmuh.minutewatcher.buildlogic.SdkVersions

plugins {
    id("com.android.application")
    id("minutewatcher.android.lint")
    id("minutewatcher.static-analysis")
    id("minutewatcher.test-infrastructure")
}

extensions.configure<ApplicationExtension> {
    compileSdk = SdkVersions.COMPILE_SDK

    defaultConfig {
        minSdk = SdkVersions.MIN_SDK
        targetSdk = SdkVersions.TARGET_SDK

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(SdkVersions.JAVA_TOOLCHAIN_VERSION)
        targetCompatibility = JavaVersion.toVersion(SdkVersions.JAVA_TOOLCHAIN_VERSION)
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
    jvmToolchain(SdkVersions.JAVA_TOOLCHAIN_VERSION)
}
