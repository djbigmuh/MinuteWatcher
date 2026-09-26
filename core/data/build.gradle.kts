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
    namespace = "com.example.minutewatcher.core.data"

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
    }

    sourceSets {
        getByName("debug") {
            java.directories.add("src/noop/java")
            kotlin.directories.add("src/noop/java")
        }
        getByName("release") {
            java.directories.add("src/noop/java")
            kotlin.directories.add("src/noop/java")
        }
        getByName("benchmark") {
            java.directories.add("src/mockBenchmark/java")
            kotlin.directories.add("src/mockBenchmark/java")
        }
    }
}

dependencies {
    implementation(libs.androidxAppcompat)
    implementation(libs.googleMaterial)
    implementation(libs.androidxMaterial3)
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:resources"))
    implementation(project(":core:android"))

    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)
    implementation(libs.androidxHiltWorker)
    ksp(libs.androidxHiltCompilerWorker)

    implementation(libs.androidxDatastorePreferences)
    implementation(libs.androidxWorkRuntimeKtx)
    implementation(libs.androidxCoreKtx)

    // Hilt Testing
    testImplementation(libs.daggerHiltAndroidTesting)
    kspTest(libs.daggerHiltCompiler)

    testImplementation(testFixtures(project(":core:android")))
    testImplementation(testFixtures(project(":core:common")))
    testImplementation(libs.junit)
    testImplementation(libs.mockingCore)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.kotlinxCoroutinesTesting)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxWorkTesting)
    testImplementation(libs.androidxTestCoreKtx)
}
