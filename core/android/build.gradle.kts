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
    namespace = "com.example.minutewatcher.core.android"

    defaultConfig {
    }

    @Suppress("UnstableApiUsage")
    testFixtures {
        enable = true
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
            isReturnDefaultValues = true
        }
    }

    tasks.withType<Test>().configureEach {
        // Phase 2: Structural acceleration.
        // Robolectric sandboxes are reused within the same JVM.
        // SingletonResetRule handles state cleanup.
        // forkEvery = 10 removed for stability
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:resources"))

    implementation(platform(libs.androidxComposeBom))
    implementation(libs.androidxComposeRuntime)
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppcompat)
    implementation(libs.googleMaterial)
    implementation(libs.androidxMaterial3)
    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxTestCoreKtx)
    testImplementation(libs.androidxJunit)
    testImplementation(testFixtures(project(":core:common")))

    testFixturesApi(libs.junit)
    testFixturesImplementation(project(":core:common"))
    testFixturesImplementation(platform(libs.androidxComposeBom))
    testFixturesImplementation(libs.androidxComposeRuntime)
}
