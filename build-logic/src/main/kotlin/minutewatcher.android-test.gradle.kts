import com.android.build.api.dsl.TestExtension
import com.github.djbigmuh.minutewatcher.buildlogic.SdkVersions

plugins {
    id("com.android.test")
    id("minutewatcher.android.lint")
    id("minutewatcher.static-analysis")
    id("minutewatcher.test-infrastructure")
}

extensions.configure<TestExtension> {
    compileSdk {
        version = release(SdkVersions.COMPILE_SDK) {
            minorApiLevel = SdkVersions.COMPILE_MINOR_SDK
        }
    }

    defaultConfig {
        minSdk = SdkVersions.MIN_SDK
        targetSdk = SdkVersions.TARGET_SDK

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(SdkVersions.JAVA_TOOLCHAIN_VERSION)
        targetCompatibility = JavaVersion.toVersion(SdkVersions.JAVA_TOOLCHAIN_VERSION)
    }
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
    jvmToolchain(SdkVersions.JAVA_TOOLCHAIN_VERSION)
}
