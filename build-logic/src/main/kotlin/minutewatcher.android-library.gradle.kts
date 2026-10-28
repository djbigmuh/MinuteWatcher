import com.android.build.api.dsl.LibraryExtension
import host.serv.djbigmuh.minutewatcher.buildlogic.SdkVersions

plugins {
    id("com.android.library")
    id("minutewatcher.android.lint")
    id("minutewatcher.static-analysis")
    id("minutewatcher.test-infrastructure")
}

extensions.configure<LibraryExtension> {
    compileSdk = SdkVersions.COMPILE_SDK

    defaultConfig {
        minSdk = SdkVersions.MIN_SDK

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
