import com.android.build.api.dsl.LibraryExtension
import com.example.minutewatcher.buildlogic.SdkVersions

plugins {
    id("com.android.library")
    id("minutewatcher.android.lint")
}

extensions.configure<LibraryExtension> {
    compileSdk = SdkVersions.COMPILE_SDK

    defaultConfig {
        minSdk = SdkVersions.MIN_SDK

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}
