import com.android.build.api.dsl.LibraryExtension
import com.example.minutewatcher.buildlogic.SdkVersions

plugins {
    id("com.android.library")
    id("minutewatcher.android.lint")
    id("minutewatcher.static-analysis")
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
