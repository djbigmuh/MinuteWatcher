plugins {
    id("minutewatcher.android-library")
    alias(libs.plugins.kotlinKsp)
}

android {
    namespace = "host.serv.djbigmuh.minutewatcher.core.common"

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:resources"))
    implementation(libs.androidxCoreKtx)
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
    testFixturesApi(libs.androidxCoreKtx)
    testFixturesApi(libs.androidxAppcompat)
    testFixturesApi(libs.androidxWorkTesting)
    testFixturesApi(libs.androidxWorkRuntimeKtx)
    testFixturesApi(libs.androidxLifecycleProcess)
    testFixturesApi(libs.androidxLifecycleRuntimeKtx)
    testFixturesApi(libs.mockingKotlin)
    testFixturesApi(libs.kotlinxCoroutinesTesting)
    testFixturesApi(libs.junit)
}
