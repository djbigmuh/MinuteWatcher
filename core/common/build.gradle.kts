import com.example.minutewatcher.buildlogic.SdkVersions

plugins {
    id("minutewatcher.android-library")
    alias(libs.plugins.kotlinKsp)
}

kotlin {
    jvmToolchain(SdkVersions.JAVA_TOOLCHAIN_VERSION)
}

android {
    namespace = "com.example.minutewatcher.core.common"

    @Suppress("UnstableApiUsage")
    testFixtures {
        enable = true
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:resources"))
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppcompat)
    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)
    api(libs.kotlinxCoroutinesCore)
    implementation(libs.androidxLifecycleViewmodelKtx)
    implementation(libs.javaxInject)

    testImplementation(libs.junit)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.mockingCore)
    testImplementation(libs.kotlinxCoroutinesTesting)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxTestCoreKtx)

    testFixturesApi(libs.androidxDatastorePreferences)
    testFixturesApi(libs.mockingKotlin)
    testFixturesApi(libs.kotlinxCoroutinesTesting)
    testFixturesApi(libs.junit)
}
