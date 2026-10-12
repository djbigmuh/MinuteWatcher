@file:Suppress("UnstableApiUsage")

plugins {
    id("minutewatcher.android-library")
}

android {
    namespace = "com.example.minutewatcher.core.resources"

    testFixtures {
        enable = true
    }
}

dependencies {
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppcompat)
    implementation(libs.googleMaterial)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxTestCoreKtx)
    testImplementation(testFixtures(project(":core:resources")))
}
