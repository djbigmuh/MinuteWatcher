// DO NOT DOWNGRADE SDK VERSIONS. minSdk 26, targetSdk 36, compileSdk 37 are project requirements.
plugins {
    id("minutewatcher.android-library")
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlinKsp)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.example.minutewatcher.core.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:resources"))

    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppcompat)
    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxTestCoreKtx)
    testImplementation(libs.androidxJunit)
}


