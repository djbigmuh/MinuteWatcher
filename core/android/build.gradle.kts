import com.example.minutewatcher.buildlogic.SdkVersions

plugins {
    id("minutewatcher.android-library")
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlinKsp)
    alias(libs.plugins.kotlinCompose)
}

kotlin {
    jvmToolchain(SdkVersions.JAVA_TOOLCHAIN_VERSION)
}

android {
    namespace = "com.example.minutewatcher.core.android"

    testFixtures {
        enable = true
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:resources"))

    implementation(platform(libs.androidxComposeBom))
    implementation(libs.androidxComposeRuntime)
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppcompat)
    implementation(libs.googleMaterial)
    implementation(libs.androidxMaterial3)
    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxTestCoreKtx)
    testImplementation(libs.androidxJunit)
    testImplementation(testFixtures(project(":core:common")))

    testFixturesApi(libs.junit)
    testFixturesImplementation(testFixtures(project(":core:common")))
    testFixturesImplementation(platform(libs.androidxComposeBom))
    testFixturesImplementation(libs.androidxComposeRuntime)
}
