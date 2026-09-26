import com.example.minutewatcher.buildlogic.SdkVersions

plugins {
    id("minutewatcher.android-library")
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlinKsp)
}

kotlin {
    jvmToolchain(SdkVersions.JAVA_TOOLCHAIN_VERSION)
}

android {
    namespace = "com.example.minutewatcher.feature.semantic"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

ksp {
    arg("androidx.appsearch.compiler.generateKotlin", "false")
    arg("androidx.appsearch.compiler.incremental", "false")
}

dependencies {
    implementation(libs.androidxAppcompat)
    implementation(libs.androidxMaterial3)
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:android"))
    implementation(project(":core:resources"))

    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)

    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxDatastorePreferences)
    implementation(libs.androidxAppsearch)
    ksp(libs.androidxAppsearchCompiler)
    implementation(libs.androidxAppsearchLocalStorage)
    implementation(libs.kotlinxCoroutinesGuava)

    testImplementation(testFixtures(project(":core:android")))
    testImplementation(testFixtures(project(":core:common")))
    testImplementation(libs.junit)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxTestCoreKtx)
    testImplementation(libs.kotlinxCoroutinesTesting)
}
