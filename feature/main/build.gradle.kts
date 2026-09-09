plugins {
    id("minutewatcher.android-library")
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlinKsp)
    alias(libs.plugins.kotlinCompose)
    id("kotlin-parcelize")
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.example.minutewatcher.feature.main"

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

    tasks.withType<Test>().configureEach {
        // Phase 2: Structural acceleration.
        // Robolectric sandboxes are reused within the same JVM.
        forkEvery = 10
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

    implementation(libs.androidxAppcompat)
    implementation(libs.googleMaterial)
    implementation(platform(libs.androidxComposeBom))
    implementation(libs.androidxUi)
    implementation(libs.androidxMaterial3)
    implementation(libs.androidxMaterialIconsCore)
    implementation(libs.androidxMaterialIconsExtended)
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxLifecycleViewmodelKtx)
    implementation(libs.androidxLifecycleRuntimeCompose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxUiTestJunit4)
    androidTestImplementation(libs.androidxUiTestJunit4)
    debugImplementation(libs.androidxUiTestManifest)
    testImplementation(libs.mockingKotlin)
    testImplementation(testFixtures(project(":core:android")))
    testImplementation(testFixtures(project(":core:common")))
    androidTestImplementation(libs.androidxJunit)
    androidTestImplementation(libs.androidxEspressoCore)
}
