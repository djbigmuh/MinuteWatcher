// DO NOT DOWNGRADE SDK VERSIONS. minSdk 26, targetSdk 36, compileSdk 37 are project requirements.
plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlinKsp)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.example.minutewatcher.feature.semantic"
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
    implementation(libs.androidxAppcompat)
    implementation(libs.androidxMaterial3)
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
    implementation(project(":core:model"))

    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)

    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppsearch)
    ksp(libs.androidxAppsearchCompiler)
    implementation(libs.androidxAppsearchLocalStorage)
    implementation(libs.kotlinxCoroutinesGuava)

    testImplementation(libs.junit)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxTestCoreKtx)
    testImplementation(libs.kotlinxCoroutinesTesting)
}
