import com.android.build.api.dsl.ApplicationExtension
import com.github.djbigmuh.minutewatcher.buildlogic.SdkVersions

plugins {
    id("com.android.application")
    id("minutewatcher.android.lint")
    id("minutewatcher.static-analysis")
    id("minutewatcher.test-infrastructure")
}

extensions.configure<ApplicationExtension> {
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

    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = true
        }
        create("benchmark") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug", "release")
            enableUnitTestCoverage = true
        }
    }

    sourceSets {
        getByName("testBenchmark") {
            java.directories.add("src/testBenchmark/java")
            kotlin.directories.add("src/testBenchmark/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(SdkVersions.JAVA_TOOLCHAIN_VERSION)
        targetCompatibility = JavaVersion.toVersion(SdkVersions.JAVA_TOOLCHAIN_VERSION)
    }

    // Disabling language splits is required for app language selection support on API 26-32.
    @Suppress("UnstableApiUsage")
    bundle {
        language {
            enableSplit = false
        }
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
