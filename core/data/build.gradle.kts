plugins {
    id("minutewatcher.android-library")
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlinKsp)
}

android {
    namespace = "host.serv.djbigmuh.minutewatcher.core.data"

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
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
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:resources"))
    implementation(project(":core:android"))

    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)
    kspTestFixtures(libs.daggerHiltCompiler)
    implementation(libs.androidxHiltWorker)
    ksp(libs.androidxHiltCompilerWorker)

    implementation(libs.androidxDatastorePreferences)
    implementation(libs.androidxWorkRuntimeKtx)
    implementation(libs.androidxCoreKtx)

    testImplementation(testFixtures(project(":core:android")))
    testImplementation(testFixtures(project(":core:common")))
    testImplementation(libs.junit)
    testImplementation(libs.mockingCore)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.kotlinxCoroutinesTesting)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxWorkTesting)
    testImplementation(libs.androidxTestCoreKtx)
    testImplementation(libs.androidxAppcompat)

    testFixturesApi(libs.junit)
    testFixturesApi(libs.mockingCore)
    testFixturesApi(libs.mockingKotlin)
    testFixturesApi(libs.kotlinxCoroutinesTesting)
    testFixturesApi(libs.androidxDatastorePreferences)
    testFixturesApi(libs.androidxCoreKtx)
    testFixturesApi(libs.androidxWorkRuntimeKtx)
    testFixturesApi(project(":core:common"))
    testFixturesApi(project(":core:domain"))
    testFixturesApi(project(":core:android"))
    testFixturesApi(project(":core:model"))
    testFixturesImplementation(testFixtures(project(":core:common")))
}
