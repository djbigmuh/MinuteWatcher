plugins {
    id("minutewatcher.android-library")
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.example.minutewatcher.core.domain"

    defaultConfig {
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
        // Phase 2: Performance optimization.
        // Module has some Robolectric tests but no complex global state.
        forkEvery = 0
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.javaxInject)
    api(libs.kotlinxCoroutinesCore)

    testImplementation(libs.junit)
    testImplementation(libs.mockingCore)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.kotlinxCoroutinesTesting)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxTestCoreKtx)
}
