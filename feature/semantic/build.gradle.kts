// DO NOT DOWNGRADE SDK VERSIONS. minSdk 26, targetSdk 37, compileSdk 37 are project requirements.
plugins {
    id("minutewatcher.android-library")
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.kotlinKsp)
}

kotlin {
    jvmToolchain(21)
}

ksp {
    arg("appsearch.compiler.incremental", "false")
}

ksp {
    arg("appsearch.compiler.incremental", "false")
}

ksp {
    arg("appsearch.compiler.incremental", "false")
}

android {
    namespace = "com.example.minutewatcher.feature.semantic"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
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
    implementation(libs.androidxAppcompat)
    implementation(libs.androidxMaterial3)
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:android"))
    implementation(project(":core:resources"))

    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)

    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppsearch)
    ksp(libs.androidxAppsearchCompiler)
    implementation(libs.androidxAppsearchLocalStorage)
    implementation(libs.kotlinxCoroutinesGuava)

    testImplementation(testFixtures(project(":core:common")))
    testImplementation(libs.junit)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidxTestCoreKtx)
    testImplementation(libs.kotlinxCoroutinesTesting)
}

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.sdk", 35)
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
    )
}
