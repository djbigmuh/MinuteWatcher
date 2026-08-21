// DO NOT DOWNGRADE SDK VERSIONS. minSdk 26, targetSdk 37, compileSdk 37 are project requirements.
plugins {
    id("minutewatcher.android-library")
    alias(libs.plugins.kotlinKsp)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.example.minutewatcher.core.common"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    @Suppress("UnstableApiUsage")
    testFixtures {
        // testFixtures is @Incubating in Gradle 9.x but is essential for cross-module
        // test data sharing. Suppression is intentional and follows best practices
        // for multi-module Android projects.
        // AGP 9.3.1 внутренне генерирует 'Project object as a dependency notation' warning при включённых testFixtures.
        // Это известная проблема AGP, не исправляется на стороне проекта. Игнорировать до выхода AGP 9.4+.
        enable = true
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:resources"))
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppcompat)
    implementation(libs.daggerHiltAndroid)
    ksp(libs.daggerHiltCompiler)
    api(libs.kotlinxCoroutinesCore)
    implementation(libs.javaxInject)

    testImplementation(libs.junit)
    testImplementation(libs.mockingKotlin)
    testImplementation(libs.mockingCore)
    testImplementation(libs.kotlinxCoroutinesTesting)
}
