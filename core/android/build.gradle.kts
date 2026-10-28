plugins {
    id("minutewatcher.android-library")
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlinKsp)
    alias(libs.plugins.kotlinCompose)
}

android {
    namespace = "host.serv.djbigmuh.minutewatcher.core.android"

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
    implementation(libs.androidxUi)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppcompat)
    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxTestCoreKtx)
    testImplementation(libs.androidxJunit)
    testImplementation(testFixtures(project(":core:common")))
    testImplementation(testFixtures(project(":core:resources")))

    testFixturesApi(libs.junit)
    testFixturesImplementation(testFixtures(project(":core:common")))
    testFixturesImplementation(platform(libs.androidxComposeBom))
    testFixturesImplementation(libs.androidxComposeRuntime)
}
