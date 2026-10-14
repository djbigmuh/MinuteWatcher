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
    namespace = "com.example.minutewatcher.feature.widget"

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidxAppcompat)
    implementation(libs.kotlinxCoroutinesCore)
    implementation(libs.googleMaterial)
    implementation(libs.androidxMaterial3)
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:android"))
    implementation(project(":core:resources"))

    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)

    implementation(libs.androidxCoreKtx)
    implementation(platform(libs.androidxComposeBom))
    implementation(libs.androidxComposeRuntime)
    implementation(libs.androidxUi)
    implementation(libs.androidxGlanceAppwidget)
    implementation(libs.androidxGlanceMaterial3)

    testImplementation(libs.junit)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.androidxTestCoreKtx)
    testImplementation(libs.androidxTestRunner)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinxCoroutinesTesting)
    testImplementation(testFixtures(project(":core:android")))
    testImplementation(testFixtures(project(":core:common")))
}
