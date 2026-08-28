plugins {
    id("minutewatcher.android-library")
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlinKsp)
    alias(libs.plugins.kotlinCompose)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.example.minutewatcher.feature.settings"

    defaultConfig {
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:android"))
    implementation(project(":core:resources"))

    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)

    implementation(platform(libs.androidxComposeBom))
    implementation(libs.androidxUi)
    implementation(libs.androidxMaterial3)
    implementation(libs.androidxMaterialIconsCore)
    implementation(libs.androidxMaterialIconsExtended)
    implementation(libs.skydovesColorpickerview)
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppcompat)
    implementation(libs.googleMaterial)
    implementation(libs.androidxLifecycleViewmodelKtx)
    implementation(libs.androidxLifecycleRuntimeCompose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxUiTestJunit4)
    debugImplementation(libs.androidxUiTestManifest)
    testImplementation(libs.mockingCore)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.kotlinxCoroutinesTesting)
    testImplementation(testFixtures(project(":core:common")))
}
